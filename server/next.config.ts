import type { NextConfig } from "next";

const config: NextConfig = {
  // postgres.js and argon2 use Node APIs / native bindings; keep them out of the bundle.
  serverExternalPackages: ["postgres", "@node-rs/argon2"],
};

export default config;
