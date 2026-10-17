// last requests that hit the IoT hook - only for finding out what the robot sends.
// in memory on purpose, gone on restart
export type IotEntry = {
  id: number;
  at: string;
  method: string;
  query: string;
  contentType?: string;
  userAgent?: string;
  from?: string;
  raw: string;
};

const g = globalThis as unknown as { __iotLog?: IotEntry[]; __iotSeq?: number };

export function recordIot(entry: Omit<IotEntry, "id" | "at">): IotEntry {
  const log = (g.__iotLog ??= []);
  g.__iotSeq = (g.__iotSeq ?? 0) + 1;
  const full = { ...entry, id: g.__iotSeq, at: new Date().toISOString() };
  log.unshift(full);
  log.length = Math.min(log.length, 50);
  return full;
}

export function iotLog(): IotEntry[] {
  return g.__iotLog ?? [];
}
