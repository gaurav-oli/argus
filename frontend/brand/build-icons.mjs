// Renders the Argus icon PNGs (and favicon.ico) from the SVG sources in this folder.
// Run from frontend/: `node brand/build-icons.mjs`. Uses sharp, which Next.js already installs.
import { readFile, writeFile } from "node:fs/promises";
import sharp from "sharp";

const here = new URL(".", import.meta.url);
const src = (f) => readFile(new URL(f, here));
const out = (p) => new URL(`../${p}`, here);

async function png(svgFile, size) {
  return sharp(await src(svgFile), { density: 384 }).resize(size, size).png().toBuffer();
}

/** A minimal .ico holding PNG images (supported by every current browser). */
function ico(images) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(images.length, 4);
  const entries = [];
  let offset = 6 + 16 * images.length;
  for (const { size, data } of images) {
    const e = Buffer.alloc(16);
    e.writeUInt8(size >= 256 ? 0 : size, 0);
    e.writeUInt8(size >= 256 ? 0 : size, 1);
    e.writeUInt16LE(1, 4);
    e.writeUInt16LE(32, 6);
    e.writeUInt32LE(data.length, 8);
    e.writeUInt32LE(offset, 12);
    offset += data.length;
    entries.push(e);
  }
  return Buffer.concat([header, ...entries, ...images.map((i) => i.data)]);
}

await writeFile(out("public/icon-192.png"), await png("app-icon.svg", 192));
await writeFile(out("public/icon-512.png"), await png("app-icon.svg", 512));
await writeFile(out("src/app/apple-icon.png"), await png("app-icon.svg", 180));
await writeFile(out("public/badge-96.png"), await png("badge.svg", 96));
await writeFile(out("public/icon.svg"), await src("app-icon.svg"));
await writeFile(out("src/app/icon.svg"), await src("favicon.svg"));
await writeFile(
  out("src/app/favicon.ico"),
  ico([
    { size: 16, data: await png("favicon.svg", 16) },
    { size: 32, data: await png("favicon.svg", 32) },
    { size: 48, data: await png("favicon.svg", 48) },
  ]),
);
console.log("icons written");
