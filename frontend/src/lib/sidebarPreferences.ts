export interface SidebarPreferences {
  selectedRoot: string | null;
  openFolders: Record<string, string[]>;
}

const STORAGE_KEY = "pb-sidebar-state-v1";

let sessionValue: string | null = null;

function emptyPreferences(): SidebarPreferences {
  return { selectedRoot: null, openFolders: {} };
}

function decodePreferences(stored: string): SidebarPreferences {
  const decoded: unknown = JSON.parse(stored);
  if (typeof decoded !== "object" || decoded === null) return emptyPreferences();
  const candidate = decoded as Record<string, unknown>;
  if (candidate.selectedRoot !== null && typeof candidate.selectedRoot !== "string") return emptyPreferences();
  if (typeof candidate.openFolders !== "object" || candidate.openFolders === null || Array.isArray(candidate.openFolders)) {
    return emptyPreferences();
  }
  const openFolders = candidate.openFolders as Record<string, unknown>;
  if (Object.values(openFolders).some((paths) => !Array.isArray(paths) || paths.some((path) => typeof path !== "string"))) {
    return emptyPreferences();
  }
  return {
    selectedRoot: candidate.selectedRoot,
    openFolders: openFolders as Record<string, string[]>,
  };
}

export function readSidebarPreferences(): SidebarPreferences {
  let stored: string | null;
  try {
    stored = sessionStorage.getItem(STORAGE_KEY);
  } catch {
    stored = sessionValue;
  }
  if (stored === null) return emptyPreferences();
  try {
    return decodePreferences(stored);
  } catch {
    return emptyPreferences();
  }
}

export function writeSidebarPreferences(value: SidebarPreferences): void {
  const serialized = JSON.stringify(value);
  sessionValue = serialized;
  try {
    sessionStorage.setItem(STORAGE_KEY, serialized);
  } catch {
    // sessionValue carries the choice while this module remains mounted.
  }
}
