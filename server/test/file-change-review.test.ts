import assert from "node:assert/strict";
import test from "node:test";
import { fileChangeReview } from "../src/file-change-review.js";
const request = { threadId: "t", turnId: "u", itemId: "i" };
const event = (path = "/project/report.txt", diff = "+hello") => ({ method: "item/started", params: {
  threadId: "t", turnId: "u", item: { id: "i", type: "fileChange", changes: [{ path, kind: { type: "add" }, diff }] },
} });
test("file review matches exact task, turn and item and exposes project-relative paths", () => {
  assert.deepEqual(fileChangeReview(request, [event()], "/project"), {
    status: "available", changes: [{ path: "report.txt", kind: "add", diff: "+hello" }],
  });
  for (const key of ["threadId", "turnId", "itemId"]) assert.equal(fileChangeReview({ ...request, [key]: "other" }, [event()], "/project").status, "unavailable");
});
test("missing, external, empty and oversized patches cannot be presented as fully reviewed", () => {
  for (const events of [[], [event("/outside/secret")], [event("../../outside")], [event("/project/a", "")], [event("/project/a", "+".repeat(131073))]])
    assert.equal(fileChangeReview(request, events, "/project").status, "unavailable");
  assert.equal(fileChangeReview(request, [event()]).status, "unavailable");
});

test("multi-file review retains delete and rename destinations and rejects a mixed external move", () => {
  const started = event();
  started.params.item.changes = [
    { path: "/project/update.txt", kind: { type: "update" }, diff: "-before\n+after" },
    { path: "/project/delete.txt", kind: { type: "delete" }, diff: "deleted contents" },
    { path: "/project/old.txt", kind: { type: "update", move_path: "/project/new.txt" } as any, diff: "-old\n+new" },
  ];
  const review = fileChangeReview(request, [started], "/project");
  assert.equal(review.status, "available");
  assert.deepEqual(review.changes.map((c: any) => [c.path, c.kind, c.movePath]), [
    ["update.txt", "update", undefined], ["delete.txt", "delete", undefined], ["old.txt", "update", "new.txt"],
  ]);
  (started.params.item.changes[2].kind as any).move_path = "/outside/new.txt";
  const rejected = fileChangeReview(request, [started], "/project");
  assert.equal(rejected.status, "unavailable");
  assert.equal(rejected.changes, undefined);
});


test("rename display footer cannot expose absolute host destinations or alter content lines", () => {
  const started = event("/project/old.txt", "@@ -1 +1 @@\n-/project/new.txt\n+new\n\nMoved to: /project/new.txt\n");
  (started.params.item.changes[0].kind as any) = { type: "update", move_path: "/project/new.txt" };
  const review = fileChangeReview(request, [started], "/project");
  assert.equal(review.status, "available");
  assert.equal(review.changes[0].diff, "@@ -1 +1 @@\n-/project/new.txt\n+new\n\nMoved to: new.txt\n");
});
