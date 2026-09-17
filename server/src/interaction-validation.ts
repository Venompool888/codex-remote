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
    if (result.action === "accept" && params.mode !== "url") validateForm(params.requestedSchema, result.content);
  } else if (["item/commandExecution/requestApproval", "item/fileChange/requestApproval"].includes(method)) {
    if (!["accept", "decline", "cancel"].includes(result.decision)) throw new Error("Unsupported approval decision");
    if (params.availableDecisions != null &&
        (!Array.isArray(params.availableDecisions) || !params.availableDecisions.includes(result.decision))) {
      throw new Error("This approval decision was not offered by the host");
    }
  } else if (method === "item/permissions/requestApproval") {
    if (result.scope !== "turn") throw new Error("Only turn-scoped permission replies are supported");
    if (!result.permissions || typeof result.permissions !== "object" || Array.isArray(result.permissions)) throw new Error("Invalid permissions");
    if (Object.keys(result.permissions).length && JSON.stringify(result.permissions) !== JSON.stringify(params.permissions)) {
      throw new Error("Permission reply must match the requested permissions exactly");
    }
  } else throw new Error("Unsupported interaction; handle it on the host");
}

export function validateForm(schema: any, content: any): void {
  if (schema?.type !== "object" || !content || typeof content !== "object" || Array.isArray(content)) throw new Error("Unsupported form schema");
  const properties = schema.properties || {};
  for (const key of schema.required || []) if (!(key in content)) throw new Error("Complete every required field");
  for (const [key, value] of Object.entries(content)) {
    const field = properties[key];
    if (!field) throw new Error("Unknown form field");
    if (field.type === "string") {
      if (typeof value !== "string" || value.length < (field.minLength || 0) || value.length > (field.maxLength ?? 20_000)) throw new Error("Invalid text field");
      if (field.pattern) throw new Error("Unsupported text pattern; handle it on the host");
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
    } else throw new Error("Unsupported form field; handle it on the host");
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
