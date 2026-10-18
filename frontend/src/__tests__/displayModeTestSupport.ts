type ChangeListener = NonNullable<MediaQueryList["onchange"]>;

export function fakeSystem() {
  let matches = false;
  let throws = false;
  let adds = 0;
  let removes = 0;
  const listeners = new Set<ChangeListener>();

  function add(listener: ChangeListener | null): void {
    if (!listener) return;
    adds++;
    listeners.add(listener);
  }

  function remove(listener: ChangeListener | null): void {
    if (!listener) return;
    removes++;
    listeners.delete(listener);
  }

  const media = {
    get matches() { return matches; },
    media: "(prefers-color-scheme: dark)",
    onchange: null,
    addEventListener(type: string, listener: ChangeListener) {
      if (type === "change") add(listener);
    },
    removeEventListener(type: string, listener: ChangeListener) {
      if (type === "change") remove(listener);
    },
    addListener: add,
    removeListener: remove,
    dispatchEvent(event: MediaQueryListEvent) {
      for (const listener of [...listeners]) listener.call(media, event);
      return !event.defaultPrevented;
    },
  } as MediaQueryList;

  return {
    get matches() { return matches; },
    set matches(value: boolean) { matches = value; },
    get throws() { return throws; },
    set throws(value: boolean) { throws = value; },
    get activeListeners() { return listeners.size; },
    get adds() { return adds; },
    get removes() { return removes; },
    matchMedia(query: string): MediaQueryList {
      if (!query.includes("prefers-color-scheme")) {
        return {
          matches: false,
          media: query,
          onchange: null,
          addEventListener: () => undefined,
          removeEventListener: () => undefined,
          addListener: () => undefined,
          removeListener: () => undefined,
          dispatchEvent: () => false,
        } as MediaQueryList;
      }
      if (throws) throw new Error("System color-scheme query failed");
      return media;
    },
    dispatch(): void {
      media.dispatchEvent(Object.assign(new Event("change"), { matches, media: media.media }));
    },
  };
}
