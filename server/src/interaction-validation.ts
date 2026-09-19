/** Validates phone replies before passing them to the host App Server. */
export function validateInteractionReply(method: string, params: any, result: any): void {
  if (!result || typeof result !== "object" || Array.isArray(result)) throw new Error("An interaction response object is required");
  if (method === "item/tool/requestUserInput") {
    const questions = params.questions;
    if (!Array.isArray(questions) || !result.answers || typeof result.answers !== "object") throw new Error("Answers are required");
    for (const question of questions) {
      const answers = result.answers[question.id]?.answers;
      if (!Array.isArray(answers) || answers.length !== 1 || typeof answers[0] !== "string" || !answers[0].trim() || answers[0].length > 20_000) {
        throw new Error("Answer every question before submitting");
      }
    }
    if (Object.keys(result.answers).some((key) => !questions.some((q: any) => q.id === key))) throw new Error("Unknown question");
  } else if (method === "mcpServer/elicitation/request") {
    if (!["accept", "decline", "cancel"].includes(result.action)) throw new Error("Invalid elicitation action");
    if (result.action === "accept" && params.mode !== "url") validateForm(params.requestedSchema, result.content, params.mode === "openai/form");
  } else if (["item/commandExecution/requestApproval", "item/fileChange/requestApproval"].includes(method)) {
    validateApprovalDecision(method, params, result.decision);
  } else if (method === "item/permissions/requestApproval") {
    if (result.scope !== "turn") throw new Error("Only turn-scoped permission replies are supported");
    if (!result.permissions || typeof result.permissions !== "object" || Array.isArray(result.permissions)) throw new Error("Invalid permissions");
    if (!permissionSubset(result.permissions, params.permissions)) throw new Error("Permission reply exceeds the requested permissions");
    if (result.strictAutoReview != null && typeof result.strictAutoReview !== "boolean") throw new Error("Invalid strict auto-review choice");
  } else if (method === "item/tool/call") {
    validateDynamicToolResponse(result);
  } else throw new Error("Unsupported interaction; handle it on the host");
}

export function validateForm(schema: any, content: any, allowExtended = true): void {
  validateFormValue(schema, content, 0, allowExtended);
}

function validateFormValue(schema: any, content: any, depth: number, allowExtended: boolean): void {
  if (depth > 4 || !schema || typeof schema !== "object") throw new Error("Unsupported form schema");
  if (schema.type !== "object" || !content || typeof content !== "object" || Array.isArray(content)) throw new Error("Unsupported form schema");
  if (schema.additionalProperties === true) throw new Error("Unsupported open-ended form object");
  const properties = schema.properties || {};
  for (const key of schema.required || []) if (!(key in content)) throw new Error("Complete every required field");
  for (const [key, value] of Object.entries(content)) {
    const field = properties[key];
    if (!field) throw new Error("Unknown form field");
    if (field.type === "string") {
      if (typeof value !== "string" || value.length < (field.minLength || 0) || value.length > (field.maxLength ?? 20_000)) throw new Error("Invalid text field");
      if (field.pattern && (!allowExtended || !matchesSafePattern(field.pattern, value))) throw new Error("Invalid text pattern field");
      if (field.format && !validTextFormat(field.format, value)) throw new Error("Invalid " + field.format + " field");
      const allowed = field.enum || field.oneOf?.map((x: any) => x.const);
      if (allowed && !allowed.includes(value)) throw new Error("Invalid selection");
    } else if (field.type === "number" || field.type === "integer") {
      if (typeof value !== "number" || !Number.isFinite(value) || (field.type === "integer" && !Number.isInteger(value)) ||
          value < (field.minimum ?? -Infinity) || value > (field.maximum ?? Infinity)) throw new Error("Invalid number field");
    } else if (field.type === "boolean") {
      if (typeof value !== "boolean") throw new Error("Invalid boolean field");
    } else if (field.type === "array") {
      const allowed = field.items?.enum || field.items?.anyOf?.map((x: any) => x.const);
      if (!allowed || !Array.isArray(value) || value.some((x) => !allowed.includes(x)) ||
          value.length < (field.minItems || 0) || value.length > (field.maxItems ?? allowed.length) || new Set(value).size !== value.length) throw new Error("Invalid multiple selection");
    } else if (field.type === "object") {
      if (!allowExtended) throw new Error("Unsupported form field; handle it on the host");
      validateFormValue(field, value, depth + 1, allowExtended);
    } else throw new Error("Unsupported form field; handle it on the host");
  }
}

function matchesSafePattern(pattern: unknown, value: string): boolean {
  if (typeof pattern !== "string" || pattern.length < 1 || pattern.length > 256) throw new Error("Unsupported text pattern; handle it on the host");
  let index = pattern.startsWith("^") ? 1 : 0;
  const end = pattern.endsWith("$") && !pattern.endsWith("\\$") ? pattern.length - 1 : pattern.length;
  let atoms = 0;
  while (index < end) {
    const char = pattern[index];
    if (char === "\\") {
      if (++index >= end || "123456789bBAGZz".includes(pattern[index])) throw new Error("Unsupported text pattern; handle it on the host");
      index++;
    } else if (char === "[") {
      const close = pattern.indexOf("]", index + 1);
      if (close <= index + 1 || close >= end || pattern.slice(index + 1, close).includes("[")) throw new Error("Unsupported text pattern; handle it on the host");
      index = close + 1;
    } else if ("().|*+?".includes(char)) throw new Error("Unsupported text pattern; handle it on the host");
    else index++;
    atoms++;
    if (index < end && pattern[index] === "{") {
      const close = pattern.indexOf("}", index + 1);
      const bounds = close > index && close < end ? pattern.slice(index + 1, close).split(",") : [];
      if (bounds.length < 1 || bounds.length > 2 || bounds.some((x) => !/^\d+$/.test(x))) throw new Error("Unsupported text pattern; handle it on the host");
      const lower = Number(bounds[0]), upper = Number(bounds[bounds.length - 1]);
      if (lower > upper || upper > 1000) throw new Error("Unsupported text pattern; handle it on the host");
      index = close + 1;
    }
  }
  if (atoms < 1 || atoms > 128) throw new Error("Unsupported text pattern; handle it on the host");
  return new RegExp(pattern).test(value);
}

function validateApprovalDecision(method: string, params: any, decision: any): void {
  const available = params.availableDecisions;
  if (available == null) {
    if (!["accept", "decline", "cancel"].includes(decision)) throw new Error("Unsupported approval decision");
    return;
  }
  if (!Array.isArray(available) || !available.some((offered: any) => deepEqual(offered, decision))) {
    throw new Error("This approval decision was not offered by the host");
  }
  if (method === "item/fileChange/requestApproval" && typeof decision !== "string") throw new Error("Unsupported file approval decision");
  if (decision && typeof decision === "object") {
    if (decision.acceptWithExecpolicyAmendment) {
      if (!deepEqual(decision.acceptWithExecpolicyAmendment.execpolicy_amendment, params.proposedExecpolicyAmendment)) {
        throw new Error("Exec policy amendment does not match the host proposal");
      }
    } else if (decision.applyNetworkPolicyAmendment) {
      const amendment = decision.applyNetworkPolicyAmendment.network_policy_amendment;
      if (!Array.isArray(params.proposedNetworkPolicyAmendments) ||
          !params.proposedNetworkPolicyAmendments.some((offered: any) => deepEqual(offered, amendment))) {
        throw new Error("Network policy amendment does not match the host proposal");
      }
    } else throw new Error("Unsupported approval decision");
  }
}

function permissionSubset(granted: any, requested: any): boolean {
  if (!granted || typeof granted !== "object" || Array.isArray(granted) || !requested || typeof requested !== "object") return false;
  if (Object.keys(granted).some((key) => !["network", "fileSystem"].includes(key))) return false;
  for (const key of Object.keys(granted)) {
    const value = granted[key], offered = requested[key];
    if (value == null || offered == null || !structuredSubset(value, offered)) return false;
  }
  return true;
}

function structuredSubset(value: any, offered: any): boolean {
  if (Array.isArray(value)) return Array.isArray(offered) && value.every((item) => offered.some((candidate: any) => deepEqual(candidate, item)));
  if (value && typeof value === "object") {
    return offered && typeof offered === "object" && !Array.isArray(offered) &&
      Object.keys(value).every((key) => key in offered && structuredSubset(value[key], offered[key]));
  }
  return deepEqual(value, offered);
}

function deepEqual(left: any, right: any): boolean {
  if (left === right) return true;
  if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((v, i) => deepEqual(v, right[i]));
  if (left && right && typeof left === "object" && typeof right === "object" && !Array.isArray(left) && !Array.isArray(right)) {
    const keys = Object.keys(left), other = Object.keys(right);
    return keys.length === other.length && keys.every((key) => Object.prototype.hasOwnProperty.call(right, key) && deepEqual(left[key], right[key]));
  }
  return false;
}

export type ClientToolHandler = (argumentsValue: unknown) => Promise<unknown> | unknown;

export class ClientToolRegistry {
  private readonly handlers = new Map<string, ClientToolHandler>();

  register(namespace: string | null, tool: string, handler: ClientToolHandler): void {
    if (!tool || tool.length > 128 || (namespace != null && namespace.length > 128)) throw new Error("Invalid client tool identity");
    const key = this.key(namespace, tool);
    if (this.handlers.has(key)) throw new Error("Client tool already registered");
    this.handlers.set(key, handler);
  }

  async dispatch(params: any): Promise<any> {
    const handler = this.handlers.get(this.key(params?.namespace ?? null, params?.tool));
    if (!handler) throw new Error("Unsupported client tool; handle it on the host");
    const result = await handler(params.arguments);
    validateDynamicToolResponse(result);
    return result;
  }

  private key(namespace: string | null, tool: string): string {
    return JSON.stringify([namespace, tool]);
  }
}

function validateDynamicToolResponse(result: any): void {
  if (!result || typeof result !== "object" || Array.isArray(result) || typeof result.success !== "boolean" || !Array.isArray(result.contentItems)) {
    throw new Error("Invalid client tool response");
  }
  if (Object.keys(result).some((key) => !["success", "contentItems"].includes(key))) throw new Error("Invalid client tool response");
  for (const item of result.contentItems) {
    if (!item || typeof item !== "object" || Array.isArray(item)) throw new Error("Invalid client tool output");
    const keys = Object.keys(item).sort().join(",");
    const valid = item.type === "inputText" && keys === "text,type" && typeof item.text === "string" ||
      item.type === "inputImage" && keys === "imageUrl,type" && typeof item.imageUrl === "string" ||
      item.type === "inputAudio" && keys === "audioUrl,type" && typeof item.audioUrl === "string";
    if (!valid) throw new Error("Invalid client tool output");
  }
}

function validTextFormat(format: string, value: string): boolean {
  switch (format) {
    case "email": return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
    case "uri": try { return Boolean(new URL(value).protocol); } catch { return false; }
    case "date": return /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
    case "date-time": return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/i.test(value) && Number.isFinite(Date.parse(value));
    default: throw new Error("Unsupported text format; handle it on the host");
  }
}
