import { neon } from "@neondatabase/serverless";
import { purgeCutoffs } from "./retention";
import { storeFilePath, withFile } from "./store-file";
import { memoryStore } from "./store-memory";
import { createPgStore } from "./store-pg";
import type { Store } from "./store-types";

// kept per module, not on globalThis: after a hot reload the new store code has to be used.
// the in-memory data itself lives on globalThis (store-memory.ts), so it survives anyway
let store: Store | undefined;

export type Storage = "postgres" | "file" | "memory";
let storage: Storage = "memory";

// postgres (neon) when DATABASE_URL is set. otherwise in memory, with a copy in a json file on this computer
// (STORE_FILE, default .data/store.json; STORE_FILE=off = memory only)
export function getStore(): Store {
  if (store) return store;
  const url = process.env.DATABASE_URL;
  if (url) {
    const sql = neon(url);
    storage = "postgres";
    store = createPgStore({
      query: async (text, params) => {
        try {
          return await sql.query(text, params ?? []);
        } catch (err) {
          // a missing table means the setup script was not run yet
          if (err instanceof Error && /relation .* does not exist/.test(err.message)) {
            throw new Error(`database is not set up, run npm run db:setup (${err.message})`);
          }
          throw err;
        }
      },
    });
    return store;
  }
  const file = storeFilePath();
  if (file) {
    storage = "file";
    // the demo switch "Show what patients say" is off again after a restart
    store = withFile(memoryStore, file, { cutoffs: () => purgeCutoffs(), resetSettings: { showPatientText: "false" } });
  } else {
    storage = "memory";
    store = memoryStore;
  }
  return store;
}

// where the data lives: the notifications page says what survives a restart
export function storageKind(): Storage {
  getStore();
  return storage;
}
