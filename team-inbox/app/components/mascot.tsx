import { MASCOT_SVG } from "@/lib/mascot";

export function Mascot({ size = 40, className = "" }: { size?: number; className?: string }) {
  return (
    <span
      className={`mascot inline-block shrink-0 ${className}`}
      style={{ width: size, height: size }}
      dangerouslySetInnerHTML={{ __html: MASCOT_SVG }}
    />
  );
}
