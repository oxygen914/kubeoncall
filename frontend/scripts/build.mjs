import {cp, mkdir, rm} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import path from "node:path";

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceDirectory = path.join(projectDirectory, "src");
const outputDirectory = path.join(projectDirectory, "dist");

await rm(outputDirectory, {recursive: true, force: true});
await mkdir(outputDirectory, {recursive: true});
await cp(sourceDirectory, outputDirectory, {recursive: true});

console.log(`Built static console in ${outputDirectory}`);
