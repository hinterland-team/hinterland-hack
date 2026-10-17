// applies db/schema.sql to DATABASE_URL (npm run db:setup reads .env.local). safe to run again
import { readFile } from "node:fs/promises";
import { neon } from "@neondatabase/serverless";
import { setupDatabase } from "./schema.mjs";

const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is not set (put it in .env.local)");
  process.exit(1);
}

const schema = await readFile(new URL("../db/schema.sql", import.meta.url), "utf8");
const sql = neon(url);
const count = await setupDatabase(
  (text, params) => sql.query(text, params ?? []),
  schema,
  (line) => console.log(line),
);
console.log(`done, ${count} statements`);
