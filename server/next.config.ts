import type { NextConfig } from "next";

const config: NextConfig = {
  // postgres.js and argon2 use Node APIs / native bindings; keep them out of the bundle.
  serverExternalPackages: ["postgres", "@node-rs/argon2"],
  experimental: {
    // Staff image uploads are Server Action FormData. The app checks 2 MB (2 000 000 bytes) itself; the limit leaves
    // room for the multipart envelope so a 2.1 MB file reaches the action and gets «Файл больше 2 МБ». Stays under
    // Vercel's 4.5 MB cap on function request bodies.
    // Global: applies to every Server Action (sign-in included), not only uploads, and is bounded by Vercel's 4.5 MB request cap.
    serverActions: { bodySizeLimit: "3mb" },
  },
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
      {
        // Uploaded images: the route sets the same header, but a matching rule above would replace it. If one of
        // these files is ever opened as a page, `sandbox` + `default-src 'none'` keep it from running anything.
        source: "/staff/files/:path*",
        headers: [{ key: "Content-Security-Policy", value: "default-src 'none'; sandbox; frame-ancestors 'none'" }],
      },
    ];
  },
};

export default config;
