import { createHash, randomUUID } from 'node:crypto';
import { constants, existsSync } from 'node:fs';
import { link, open, realpath, unlink, type FileHandle } from 'node:fs/promises';
import { basename, isAbsolute, relative, resolve, sep } from 'node:path';
import type { CodexAppServer } from './codex-app-server.js';
import { MAX_WORKSPACE_FILE_BYTES, validatedWorkspace } from './workspace-features.js';

type Params = Record<string, unknown>;
export type WorkspaceResolver = (value: unknown) => Promise<string>;
export const WORKSPACE_TEXT_WRITES_SUPPORTED = process.platform === 'linux' && existsSync('/proc/self/fd');

export const WORKSPACE_MUTATION_METHODS = new Set([
  'host/workspace/text/save', 'host/workspace/text/create', 'host/memory/reset',
  'host/thread/backgroundTerminals/list', 'host/thread/backgroundTerminals/terminate',
  'host/thread/backgroundTerminals/clean',
]);

export const WORKSPACE_MUTATION_WRITES = new Set([
  'host/workspace/text/save', 'host/workspace/text/create', 'host/memory/reset',
  'host/thread/backgroundTerminals/terminate', 'host/thread/backgroundTerminals/clean',
]);

/** Typed mutation wrappers only; arbitrary filesystem and App Server methods are not accepted. */
export class WorkspaceMutations {
  constructor(
    private readonly codex: Pick<CodexAppServer, 'call'>,
    private readonly resolveWorkspace: WorkspaceResolver = validatedWorkspace,
  ) {}

  async call(method: string, params: Params): Promise<unknown> {
    switch (method) {
      case 'host/workspace/text/save': return this.saveText(params);
      case 'host/workspace/text/create': return this.createText(params);
      case 'host/memory/reset': return this.resetMemory(params);
      case 'host/thread/backgroundTerminals/list': return this.listBackgroundTerminals(params);
      case 'host/thread/backgroundTerminals/terminate': return this.terminateBackgroundTerminal(params);
      case 'host/thread/backgroundTerminals/clean': return this.cleanBackgroundTerminals(params);
      default: throw new Error('Unsupported workspace mutation method');
    }
  }

  private async saveText(params: Params): Promise<unknown> {
    const target = await this.target(params.cwd, params.path);
    const bytes = textBytes(params.text);
    const expected = sha(params.expectedSha256, 'expectedSha256');
    confirmation(params, 'save_workspace_text', target.relativePath);

    await withAnchoredParent(target.root, target.components, async (parent, leaf) => {
      let handle: FileHandle;
      try { handle = await open(fdChild(parent, leaf), constants.O_RDWR | constants.O_NOFOLLOW); }
      catch { throw new Error('Workspace text file does not exist or is not a regular file'); }
      try {
        const info = await handle.stat();
        if (!info.isFile() || info.nlink !== 1) throw new Error('Workspace text file must be a regular file with one link');
        const old = await readBoundedText(handle);
        if (createHash('sha256').update(old).digest('hex') !== expected) {
          throw new Error('Workspace text changed since it was read; reload before saving');
        }
        // This binds validation and writing to one no-follow descriptor. It does
        // not lock out unrelated Host editors; expectedSha256 remains optimistic.
        await writeAll(handle, bytes, 0);
        await handle.truncate(bytes.length);
        await handle.sync();
      } finally { await handle.close(); }
    });
    return result(target.relativePath, bytes);
  }

  private async createText(params: Params): Promise<unknown> {
    const target = await this.target(params.cwd, params.path);
    const bytes = textBytes(params.text);
    confirmation(params, 'create_workspace_text', target.relativePath);
    await withAnchoredParent(target.root, target.components, async (parent, leaf) => {
      const temporary = `.codex-${randomUUID()}.tmp`;
      const temporaryPath = fdChild(parent, temporary);
      let created = false;
      try {
        const handle = await open(temporaryPath, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
        created = true;
        try { await writeAll(handle, bytes, 0); await handle.sync(); }
        finally { await handle.close(); }
        await link(temporaryPath, fdChild(parent, leaf)).catch((error: NodeJS.ErrnoException) => {
          if (error.code === 'EEXIST') throw new Error('Workspace file already exists');
          throw error;
        });
      } finally { if (created) await unlink(temporaryPath).catch(() => undefined); }
    });
    return result(target.relativePath, bytes);
  }

  private async resetMemory(params: Params): Promise<unknown> {
    confirmation(params, 'reset_memory', 'Codex memory');
    await this.codex.call('memory/reset', undefined);
    return { reset: true };
  }

  private async listBackgroundTerminals(params: Params): Promise<unknown> {
    const threadId = id(params.threadId, 'threadId');
    const limit = integer(params.limit, 1, 100, 50);
    const request: Params = { threadId, limit };
    if (params.cursor !== undefined && params.cursor !== null) request.cursor = opaque(params.cursor, 'cursor', 4096);
    const response = await this.codex.call('thread/backgroundTerminals/list', request);
    const data = object(response) && Array.isArray(response.data) ? response.data : [];
    return {
      data: data.slice(0, limit).filter(object).map((item) => ({
        itemId: id(item.itemId, 'itemId'),
        processId: id(item.processId, 'processId'),
        command: boundedText(item.command, 'command', 4096),
        cwdName: typeof item.cwd === 'string' ? basename(item.cwd).slice(0, 255) : '',
        osPid: safeInteger(item.osPid),
        cpuPercent: finiteNumber(item.cpuPercent),
        rssKb: safeInteger(item.rssKb),
      })),
      nextCursor: object(response) && typeof response.nextCursor === 'string' ? response.nextCursor : null,
    };
  }

  private async terminateBackgroundTerminal(params: Params): Promise<unknown> {
    const threadId = id(params.threadId, 'threadId');
    const processId = id(params.processId, 'processId');
    confirmation(params, 'terminate_background_terminal', `${threadId}:${processId}`);
    const response = await this.codex.call('thread/backgroundTerminals/terminate', { threadId, processId });
    return { terminated: object(response) && response.terminated === true };
  }

  private async cleanBackgroundTerminals(params: Params): Promise<unknown> {
    const threadId = id(params.threadId, 'threadId');
    confirmation(params, 'clean_background_terminals', threadId);
    await this.codex.call('thread/backgroundTerminals/clean', { threadId });
    return { cleaned: true };
  }

  private async target(cwdValue: unknown, pathValue: unknown): Promise<{ root: string; components: string[]; relativePath: string }> {
    const resolvedRoot = await this.resolveWorkspace(cwdValue);
    const root = await realpath(resolvedRoot).catch(() => { throw new Error('Workspace is unavailable'); });
    if (typeof pathValue !== 'string' || !pathValue || pathValue.length > 4096 || isAbsolute(pathValue) || /[\0\r\n]/.test(pathValue)) {
      throw new Error('A relative workspace text path is required');
    }
    const lexical = resolve(root, pathValue);
    const inside = relative(root, lexical);
    if (!inside || inside === '..' || inside.startsWith(`..${sep}`) || isAbsolute(inside)) {
      throw new Error('Workspace text path is outside the selected project');
    }
    const relativePath = inside.split(sep).join('/');
    ensureRelative(relativePath);
    return { root, components: relativePath.split('/'), relativePath };
  }
}

function confirmation(params: Params, action: string, subject: string): void {
  const value = params.confirmation;
  if (!object(value) || value.action !== action || value.subject !== subject) throw new Error(`Explicit ${action} confirmation is required`);
}

function textBytes(value: unknown): Buffer {
  if (typeof value !== 'string' || value.includes('\0')) throw new Error('Workspace content must be UTF-8 text');
  const bytes = Buffer.from(value, 'utf8');
  if (bytes.length > MAX_WORKSPACE_FILE_BYTES) throw new Error(`Workspace text exceeds ${MAX_WORKSPACE_FILE_BYTES} bytes`);
  return bytes;
}

async function readBoundedText(handle: FileHandle): Promise<Buffer> {
  const bytes = Buffer.alloc(MAX_WORKSPACE_FILE_BYTES + 1);
  let offset = 0;
  while (offset < bytes.length) {
    const { bytesRead } = await handle.read(bytes, offset, bytes.length - offset, offset);
    if (bytesRead === 0) break;
    offset += bytesRead;
  }
  const value = bytes.subarray(0, offset);
  if (offset > MAX_WORKSPACE_FILE_BYTES || value.includes(0)) throw new Error('Workspace file is not bounded UTF-8 text');
  try { new TextDecoder('utf-8', { fatal: true }).decode(value); }
  catch { throw new Error('Workspace file is not valid UTF-8 text'); }
  return value;
}

async function writeAll(handle: FileHandle, bytes: Buffer, position: number): Promise<void> {
  let offset = 0;
  while (offset < bytes.length) {
    const { bytesWritten } = await handle.write(bytes, offset, bytes.length - offset, position + offset);
    if (bytesWritten <= 0) throw new Error('Workspace text write failed');
    offset += bytesWritten;
  }
}

async function withAnchoredParent<T>(root: string, components: string[], action: (parent: FileHandle, leaf: string) => Promise<T>): Promise<T> {
  if (!WORKSPACE_TEXT_WRITES_SUPPORTED) throw new Error('Secure workspace text writes are unavailable on this Host');
  const handles: FileHandle[] = [];
  try {
    let current = await open('/', constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW); handles.push(current);
    const directories = [...root.split('/').filter(Boolean), ...components.slice(0, -1)];
    for (const component of directories) {
      try { current = await open(fdChild(current, component), constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW); }
      catch { throw new Error('Workspace parent directory is unavailable or contains a symbolic link'); }
      handles.push(current);
    }
    return await action(current, components.at(-1)!);
  } finally { for (const handle of handles.reverse()) await handle.close().catch(() => undefined); }
}
function fdChild(parent: FileHandle, name: string): string { return `/proc/self/fd/${parent.fd}/${name}`; }

function result(path: string, bytes: Buffer): Params {
  return { path, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
}

function ensureRelative(path: string): void {
  if (!path || path === '..' || path.startsWith('../') || isAbsolute(path)) throw new Error('Workspace text path is outside the selected project');
}
function sha(value: unknown, label: string): string {
  if (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) throw new Error(`${label} must be a lowercase SHA-256 digest`);
  return value;
}
function id(value: unknown, label: string): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > 256 || !/^[A-Za-z0-9._:-]+$/.test(value)) throw new Error(`${label} is invalid`);
  return value;
}
function opaque(value: unknown, label: string, max: number): string {
  if (typeof value !== 'string' || !value || value.length > max || /[\0\r\n]/.test(value)) throw new Error(`${label} is invalid`);
  return value;
}
function boundedText(value: unknown, label: string, max: number): string {
  if (typeof value !== 'string' || value.length > max || value.includes('\0')) throw new Error(`${label} is invalid`);
  return value;
}
function integer(value: unknown, min: number, max: number, fallback: number): number {
  if (value === undefined || value === null) return fallback;
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) throw new Error(`Value must be between ${min} and ${max}`);
  return value as number;
}
function safeInteger(value: unknown): number | null { return Number.isSafeInteger(value) ? value as number : null; }
function finiteNumber(value: unknown): number | null { return typeof value === 'number' && Number.isFinite(value) ? value : null; }
function object(value: unknown): value is Params { return Boolean(value) && typeof value === 'object' && !Array.isArray(value); }
