import { cp, mkdir, rm, writeFile } from "node:fs/promises";
await rm(new URL("../dist", import.meta.url), { recursive: true, force: true });
await mkdir(new URL("../dist", import.meta.url), { recursive: true });
for (const file of ["index.html", "src", "favicon.svg"])
  await cp(
    new URL(`../${file}`, import.meta.url),
    new URL(`../dist/${file}`, import.meta.url),
    { recursive: true },
  );
await writeFile(new URL("../dist/.nojekyll", import.meta.url), "");
console.log("Built dependency-free GitHub Pages site in web/dist");
