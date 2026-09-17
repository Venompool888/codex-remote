import type { DeviceRecord } from "./auth.js";

type PublicDevice = Omit<DeviceRecord, "tokenHash">;
export interface MenuActions {
  ask(prompt: string): Promise<string | null>;
  write(message: string): void;
  pair(): Promise<void>;
  list(): Promise<PublicDevice[]>;
  revoke(id: string): Promise<boolean>;
  configure?(): Promise<void>;
}

// Device names are remote input; never let them inject terminal control sequences.
function label(value: string): string {
  return value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ").slice(0, 160);
}

export async function runMenu(actions: MenuActions): Promise<void> {
  while (true) {
    actions.write("\nCodex Remote\n  1  新建连接（生成配对二维码）\n  2  查看已配对设备\n  3  配置配对公网地址\n  0  退出");
    const choice = await actions.ask("请选择：");
    if (choice === null || choice.trim() === "0") return;
    try {
      if (choice.trim() === "1") {
        actions.write("二维码和配对码仅供本人使用，五分钟后过期。生成新码会使之前未使用的码失效。");
        await actions.pair();
      } else if (choice.trim() === "2") {
        await devicesMenu(actions);
      } else if (choice.trim() === "3" && actions.configure) {
        await actions.configure();
      } else {
        actions.write("请输入 1、2、3 或 0。");
      }
    } catch {
      actions.write("操作失败。请确认 Host 正在运行，且此命令使用相同的服务用户和状态目录。");
    }
  }
}

async function devicesMenu(actions: MenuActions): Promise<void> {
  while (true) {
    const devices = (await actions.list()).filter((device) => !device.revokedAt);
    if (!devices.length) {
      actions.write("暂无已配对设备。");
      return;
    }
    actions.write("\n已配对设备：");
    devices.forEach((device, index) => {
      const status = Date.parse(device.expiresAt) <= Date.now() ? "已过期" : "已配对";
      actions.write(`  ${index + 1}  ${label(device.name)} [${status}]\n     ID: ${label(device.id)}\n     最近使用: ${label(device.lastSeenAt ?? "尚未使用")}`);
    });
    const choice = await actions.ask("输入设备序号进行管理，0 返回：");
    if (choice === null || choice.trim() === "0") return;
    if (!/^[1-9]\d*$/.test(choice.trim())) {
      actions.write("请输入有效设备序号。");
      continue;
    }
    const device = devices[Number(choice.trim()) - 1];
    if (!device) { actions.write("请输入有效设备序号。"); continue; }
    const action = await actions.ask(`\n${label(device.name)}\n  1  删除配对（撤销此设备访问权限）\n  0  返回\n请选择：`);
    if (action === null) return;
    if (action.trim() !== "1") continue;
    const confirmation = await actions.ask("该设备将无法继续访问，重新连接需要再次配对。输入 y 确认删除：");
    if (confirmation === null) return;
    if (confirmation.trim().toLowerCase() !== "y") { actions.write("已取消。"); continue; }
    // Use the selected ID, not a refreshed list index that might identify another device.
    actions.write(await actions.revoke(device.id) ? "已撤销配对。" : "该设备已被撤销或不存在。");
  }
}
