import type { NextConfig } from "next";

const config: NextConfig = {
  // postgres.js and argon2 use Node APIs / native bindings; keep them out of the bundle.
  serverExternalPackages: ["postgres", "@node-rs/argon2"],
  // The staff website must not be embeddable in another site's frame (clickjacking). The API routes are left alone.
  async headers() {
    return [
      {
        source: "/staff/:path*",
        headers: [
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
        ],
      },
    ];
  },
};

export default config;
