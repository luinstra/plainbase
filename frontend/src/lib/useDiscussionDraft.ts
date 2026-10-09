import { useRef, useState, type SetStateAction } from "react";

/** Retire only the submitted buffer; a remounted composer may already own newer unsaved text. */
export function retireDiscussionDraft(drafts: Map<string, string> | undefined, key: string, submitted: string) {
  if (drafts?.get(key) === submitted) drafts.delete(key);
}

/**
 * Page-owned keyed buffers survive disabled workspace unmounts and nondestructive All discussions
 * navigation. Explicit Cancel clears the buffer; success retires matching submitted text only.
 * Reopening an action stays a separate user choice.
 */
export function useDiscussionDraft(drafts: Map<string, string> | undefined, key: string) {
  const [draft, setDraft] = useState(() => ({ key, body: drafts?.get(key) ?? "" }));
  const body = draft.key === key ? draft.body : drafts?.get(key) ?? "";
  const current = useRef(body);
  current.current = body;
  if (draft.key !== key) setDraft({ key, body });
  const setBody = (update: SetStateAction<string>) => {
    const next = typeof update === "function" ? update(current.current) : update;
    current.current = next;
    drafts?.set(key, next);
    setDraft({ key, body: next });
  };
  return [body, setBody] as const;
}
