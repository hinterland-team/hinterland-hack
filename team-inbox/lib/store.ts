import { neon } from "@neondatabase/serverless";
import { memoryStore } from "./store-memory";
import { createPgStore } from "./store-pg";
import type { Store } from "./store-types";

// kept per module, not on globalThis: after a hot reload the new store code has to be used.
// the in-memory data itself lives on globalThis (store-memory.ts), so it survives anyway
let store: Store | undefined;

// postgres (neon) when DATABASE_URL is set, otherwise in memory for local demos
export function getStore(): Store {
  if (store) return store;
  const url = process.env.DATABASE_URL;
  if (url) {
    const sql = neon(url);
    store = createPgStore({ query: (text, params) => sql.query(text, params ?? []) });
  } else {
    store = memoryStore;
  }
  return store;
}
