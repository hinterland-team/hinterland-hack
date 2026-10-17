import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  devIndicators: false,
  experimental: {
    optimizePackageImports: ["@phosphor-icons/react"],
  },
  // places and notifications moved into Settings. old links (bookmarks, test messages) still work
  async redirects() {
    return [
      { source: "/places", destination: "/settings/places", permanent: false },
      { source: "/notifications", destination: "/settings/notifications", permanent: false },
    ];
  },
};

export default nextConfig;
