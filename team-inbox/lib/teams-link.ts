import { callPageHtml } from "./call-page";
import { ApiError } from "./http";

// teams card buttons can only open links. they carry CALL_KEY in ?k=
export function linkKeyOk(req: Request) {
  const key = process.env.CALL_KEY;
  return !key || new URL(req.url).searchParams.get("k") === key;
}

export function badLinkPage() {
  return callPageHtml({
    status: 403,
    heading: "This link does not work",
    lines: ["Please use the reception board or the room view instead."],
  });
}

// turns errors from the journey logic into a friendly page instead of json
export async function linkPage(run: () => Promise<Response>) {
  try {
    return await run();
  } catch (err) {
    if (err instanceof ApiError) {
      return callPageHtml({
        status: err.status,
        pill: err.status === 404 ? "Not found" : "Not now",
        heading: err.status === 404 ? "This ticket is gone" : "That did not work",
        lines: [err.status === 404 ? "It was finished and removed from the board." : capital(err.message) + "."],
        note: "You can close this page.",
      });
    }
    console.error(err);
    return callPageHtml({
      status: 500,
      heading: "Something went wrong",
      lines: ["Please try again or use the reception board."],
    });
  }
}

function capital(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
