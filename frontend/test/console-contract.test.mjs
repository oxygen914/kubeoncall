import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import path from "node:path";
import test from "node:test";

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceDirectory = path.join(projectDirectory, "src");

const [html, css, javascript] = await Promise.all([
    readFile(path.join(sourceDirectory, "index.html"), "utf8"),
    readFile(path.join(sourceDirectory, "styles.css"), "utf8"),
    readFile(path.join(sourceDirectory, "app.js"), "utf8"),
]);

test("JavaScript selectors match the HTML contract", () => {
    const selectorIds = [...javascript.matchAll(/querySelector\("#([^"]+)"\)/g)].map((match) => match[1]);
    const missingIds = [...new Set(selectorIds)].filter(
        (id) => !new RegExp(`id=["']${id}["']`).test(html),
    );

    assert.deepEqual(missingIds, []);
    assert.equal(new Set(selectorIds).size, 35);
});

test("HTML component classes have corresponding styles", () => {
    const classNames = [...html.matchAll(/class=["']([^"']+)["']/g)]
        .flatMap((match) => match[1].trim().split(/\s+/));
    const missingClasses = [...new Set(classNames)].filter((className) => !css.includes(`.${className}`));

    assert.deepEqual(missingClasses, []);
});

test("console assets are linked and session secrets are not persisted", () => {
    assert.match(html, /href="\.\/styles\.css"/);
    assert.match(html, /src="\.\/app\.js"/);
    assert.doesNotMatch(javascript, /localStorage|sessionStorage/);
    assert.doesNotThrow(() => new Function(javascript));
});
