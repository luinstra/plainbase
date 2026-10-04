import { fireEvent, screen } from "@testing-library/react";

type ButtonOptions = Parameters<typeof screen.getByRole>[1];

/** Follow the visible disclosure path before locating a maintenance operation. */
function revealButton(options: ButtonOptions) {
  const name = options?.name;
  if (typeof name !== "string") return;
  if (["Edit comment", "Retract comment", "Purge comment", "Purge comment (admin)"].includes(name)) {
    const trigger = screen.queryByLabelText(/Actions for .*'s comment/);
    if (trigger && !(trigger.parentElement as HTMLDetailsElement).open) fireEvent.click(trigger);
  } else if (["Refresh", "Resolve discussion", "Reopen discussion", "Reattach"].includes(name)) {
    const trigger = screen.queryByLabelText("Discussion actions");
    const outside = screen.queryAllByRole("button", options).some((button) => !button.closest("details"));
    if (trigger && !outside && !(trigger.parentElement as HTMLDetailsElement).open) fireEvent.click(trigger);
  }
}

export function discussionButton(options: ButtonOptions) {
  revealButton(options);
  // Reselect is the existing passage action once creation has started.
  if (options?.name === "Start a discussion" && !screen.queryByRole("button", options)) return screen.getByRole("button", { name: "Reselect" });
  return screen.getByRole("button", options);
}

export async function findDiscussionButton(options: ButtonOptions) {
  const name = options?.name;
  if (name === "Start a discussion" && !screen.queryByRole("button", options) && screen.queryByRole("button", { name: "Reselect" }))
    return screen.getByRole("button", { name: "Reselect" });
  if (typeof name === "string" && ["Edit comment", "Retract comment", "Purge comment", "Purge comment (admin)"].includes(name)) {
    await screen.findByLabelText(/Actions for .*'s comment/);
  } else if (typeof name === "string" && ["Resolve discussion", "Reopen discussion", "Reattach"].includes(name)) {
    await screen.findByLabelText("Discussion actions");
  } else return screen.findByRole("button", options);
  return discussionButton(options);
}
