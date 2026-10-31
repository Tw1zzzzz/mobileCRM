"use client";

import { useState } from "react";
import { IMAGE_ACCEPT, IMAGE_MAX_BYTES, IMAGE_TOO_BIG_TEXT } from "@/lib/staff/notes-shared";
import styles from "./card.module.css";

/**
 * The file field of an upload form. The size check here is only a courtesy (a message before the upload and a
 * blocked submit); the server checks the size and the type of the bytes again whatever the browser does. Without
 * JavaScript it is a plain file input.
 */
export function FileInput({ name, label }: { name: string; label: string }) {
  const [tooBig, setTooBig] = useState(false);
  return (
    <>
      <input
        type="file"
        name={name}
        accept={IMAGE_ACCEPT}
        required
        aria-label={label}
        className={styles.fileInput}
        onChange={(event) => {
          const input = event.currentTarget;
          const big = (input.files?.[0]?.size ?? 0) > IMAGE_MAX_BYTES;
          input.setCustomValidity(big ? IMAGE_TOO_BIG_TEXT : "");
          setTooBig(big);
        }}
      />
      {tooBig && (
        <span role="alert" className={styles.statusError}>
          {IMAGE_TOO_BIG_TEXT}
        </span>
      )}
    </>
  );
}
