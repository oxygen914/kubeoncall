import {mkdirSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "..");
const outputDirectory = join(repositoryRoot, "assets", "readme", "kubeoncall");
const playerSceneDirectory = process.env.LOTTIE_PLAYER_SCENE_DIR;

const width = 1320;
const height = 240;
const frameRate = 60;
const durationSeconds = 10;
const outPoint = frameRate * durationSeconds;
const motionTimelineSeconds = 2;
const motionTimeScale = (frameRate * motionTimelineSeconds) / 120;
const centerY = 120;
const firstLetterX = 102;
const letterSpacing = 124;
const strokeWidth = 11;

const zero = [0, 0];
const line = (vertices) => ({
    v: vertices,
    i: vertices.map(() => zero),
    o: vertices.map(() => zero),
    c: false,
});
const curve = (vertices, inTangents, outTangents, closed = false) => ({
    v: vertices,
    i: inTangents,
    o: outTangents,
    c: closed,
});

const glyphs = [
    {
        name: "K",
        paths: [
            line([
                [-32, -68],
                [-32, 68],
            ]),
            line([
                [-27, 0],
                [34, -68],
            ]),
            line([
                [-27, 0],
                [34, 68],
            ]),
        ],
    },
    {
        name: "U",
        paths: [
            curve(
                [
                    [-36, -68],
                    [-36, 28],
                    [0, 68],
                    [36, 28],
                    [36, -68],
                ],
                [zero, zero, [-36, 0], [0, 26], zero],
                [zero, [0, 26], [36, 0], zero, zero],
            ),
        ],
    },
    {
        name: "B",
        paths: [
            line([
                [-32, -68],
                [-32, 68],
            ]),
            curve(
                [
                    [-32, -68],
                    [-32, 0],
                ],
                [zero, [65, -10]],
                [[65, 0], zero],
            ),
            curve(
                [
                    [-32, 0],
                    [-32, 68],
                ],
                [zero, [70, 0]],
                [[70, 0], zero],
            ),
        ],
    },
    {
        name: "E",
        paths: [
            line([
                [-32, -68],
                [-32, 68],
            ]),
            line([
                [-27, -68],
                [34, -68],
            ]),
            line([
                [-27, 0],
                [24, 0],
            ]),
            line([
                [-27, 68],
                [34, 68],
            ]),
        ],
    },
    {
        name: "O",
        paths: [
            curve(
                [
                    [0, -68],
                    [38, 0],
                    [0, 68],
                    [-38, 0],
                ],
                [
                    [-21, 0],
                    [0, -37.5],
                    [21, 0],
                    [0, 37.5],
                ],
                [
                    [21, 0],
                    [0, 37.5],
                    [-21, 0],
                    [0, -37.5],
                ],
                true,
            ),
        ],
    },
    {
        name: "N",
        paths: [
            line([
                [-34, 68],
                [-34, -68],
                [34, 68],
                [34, -68],
            ]),
        ],
    },
    {
        name: "C",
        paths: [
            curve(
                [
                    [32, -52],
                    [0, -68],
                    [-38, 0],
                    [0, 68],
                    [32, 52],
                ],
                [zero, [14, 0], [0, -42], [-24, 0], [-10, 10]],
                [[-10, -10], [-24, 0], [0, 42], [14, 0], zero],
            ),
        ],
    },
    {
        name: "A",
        paths: [
            line([
                [-38, 68],
                [0, -68],
                [38, 68],
            ]),
            line([
                [-22, 22],
                [22, 22],
            ]),
        ],
    },
    {
        name: "L",
        paths: [
            line([
                [-32, -68],
                [-32, 68],
                [36, 68],
            ]),
        ],
    },
    {
        name: "L",
        paths: [
            line([
                [-32, -68],
                [-32, 68],
                [36, 68],
            ]),
        ],
    },
];

const palette = [
    {at: 0, color: "#00B8F5"},
    {at: 0.36, color: "#427BFF"},
    {at: 0.68, color: "#7D5CFF"},
    {at: 1, color: "#C45BEF"},
];

function hexToRgb(hex) {
    const value = hex.replace("#", "");
    return [
        Number.parseInt(value.slice(0, 2), 16) / 255,
        Number.parseInt(value.slice(2, 4), 16) / 255,
        Number.parseInt(value.slice(4, 6), 16) / 255,
    ];
}

function rgbToHex(rgb) {
    return `#${rgb
        .map((value) => Math.round(value * 255).toString(16).padStart(2, "0"))
        .join("")
        .toUpperCase()}`;
}

function colorAt(position) {
    const rightIndex = palette.findIndex((stop) => stop.at >= position);
    if (rightIndex <= 0) {
        return hexToRgb(palette[0].color);
    }
    const left = palette[rightIndex - 1];
    const right = palette[rightIndex];
    const amount = (position - left.at) / (right.at - left.at);
    const leftRgb = hexToRgb(left.color);
    const rightRgb = hexToRgb(right.color);
    return leftRgb.map((value, index) => value + (rightRgb[index] - value) * amount);
}

function pathLength(path) {
    let length = 0;
    for (let index = 1; index < path.v.length; index += 1) {
        length += Math.hypot(
            path.v[index][0] - path.v[index - 1][0],
            path.v[index][1] - path.v[index - 1][1],
        );
    }
    if (path.c) {
        length += Math.hypot(
            path.v[0][0] - path.v.at(-1)[0],
            path.v[0][1] - path.v.at(-1)[1],
        );
    }
    return length;
}

function easeInOut(start, end) {
    return [
        {
            t: start,
            s: [0],
            e: [100],
            o: {x: [0.65], y: [0]},
        },
        {
            t: end,
            s: [100],
            i: {x: [0.35], y: [1]},
        },
    ];
}

function transform(position = [0, 0]) {
    return {
        ty: "tr",
        p: {a: 0, k: position},
        a: {a: 0, k: [0, 0]},
        s: {a: 0, k: [100, 100]},
        r: {a: 0, k: 0},
        o: {a: 0, k: 100},
    };
}

function gradientStroke(startColor, endColor) {
    return {
        ty: "gs",
        nm: "continuous-spectrum",
        o: {a: 0, k: 100},
        w: {a: 0, k: strokeWidth},
        g: {
            p: 2,
            k: {
                a: 0,
                k: [0, ...startColor, 1, ...endColor],
            },
        },
        s: {a: 0, k: [-44, 0]},
        e: {a: 0, k: [44, 0]},
        t: 1,
        lc: 2,
        lj: 2,
        ml: 4,
    };
}

function layerForGlyph(glyph, glyphIndex) {
    const totalLength = glyph.paths.reduce((sum, path) => sum + pathLength(path), 0);
    const glyphStart = (4 + glyphIndex * 6) * motionTimeScale;
    const revealDuration = 30 * motionTimeScale;
    let elapsed = 0;
    const colorStart = colorAt(glyphIndex / glyphs.length);
    const colorEnd = colorAt((glyphIndex + 1) / glyphs.length);

    const pathGroups = glyph.paths.map((path, pathIndex) => {
        const duration = Math.max(
            6 * motionTimeScale,
            Math.round((pathLength(path) / totalLength) * revealDuration),
        );
        const start = glyphStart + elapsed;
        const end = start + duration;
        elapsed += duration;
        return {
            ty: "gr",
            nm: `${glyph.name}-stroke-${pathIndex + 1}`,
            it: [
                {
                    ty: "sh",
                    nm: `${glyph.name}-path-${pathIndex + 1}`,
                    ks: {a: 0, k: path},
                },
                gradientStroke(colorStart, colorEnd),
                {
                    ty: "tm",
                    nm: "natural-path-reveal",
                    s: {a: 0, k: 0},
                    e: {a: 1, k: easeInOut(start, end)},
                    o: {a: 0, k: 0},
                    m: 1,
                },
                transform(),
            ],
        };
    });

    return {
        ddd: 0,
        ind: glyphIndex + 1,
        ty: 4,
        nm: `glyph-${glyphIndex + 1}-${glyph.name}`,
        sr: 1,
        ks: {
            o: {a: 0, k: 100},
            r: {a: 0, k: 0},
            p: {a: 0, k: [firstLetterX + glyphIndex * letterSpacing, centerY, 0]},
            a: {a: 0, k: [0, 0, 0]},
            s: {a: 0, k: [100, 100, 100]},
        },
        ao: 0,
        shapes: [
            {
                ty: "gr",
                nm: `${glyph.name}-vector-strokes`,
                it: [...pathGroups, transform()],
            },
        ],
        ip: 0,
        op: outPoint,
        st: 0,
        bm: 0,
    };
}

function formatNumber(value) {
    return Number(value.toFixed(3));
}

function svgPathData(path, offsetX, offsetY) {
    const point = ([x, y]) => `${formatNumber(x + offsetX)} ${formatNumber(y + offsetY)}`;
    let data = `M ${point(path.v[0])}`;
    const appendSegment = (fromIndex, toIndex) => {
        const from = path.v[fromIndex];
        const to = path.v[toIndex];
        const outgoing = path.o[fromIndex];
        const incoming = path.i[toIndex];
        const isLine =
            outgoing[0] === 0 &&
            outgoing[1] === 0 &&
            incoming[0] === 0 &&
            incoming[1] === 0;
        if (isLine) {
            data += ` L ${point(to)}`;
            return;
        }
        data += ` C ${point([from[0] + outgoing[0], from[1] + outgoing[1]])}`;
        data += ` ${point([to[0] + incoming[0], to[1] + incoming[1]])}`;
        data += ` ${point(to)}`;
    };

    for (let index = 1; index < path.v.length; index += 1) {
        appendSegment(index - 1, index);
    }
    if (path.c) {
        appendSegment(path.v.length - 1, 0);
        data += " Z";
    }
    return data;
}

function buildSvg() {
    const gradientStops = palette
        .map(
            (stop) =>
                `      <stop offset="${formatNumber(stop.at * 100)}%" stop-color="${rgbToHex(hexToRgb(stop.color))}" />`,
        )
        .join("\n");
    const paths = glyphs
        .flatMap((glyph, glyphIndex) =>
            glyph.paths.map(
                (path, pathIndex) =>
                    `  <path id="glyph-${glyphIndex + 1}-${glyph.name}-${pathIndex + 1}" d="${svgPathData(path, firstLetterX + glyphIndex * letterSpacing, centerY)}" />`,
            ),
        )
        .join("\n");

    return [
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc">`,
        "  <title id=\"title\">KUBEONCALL gradient path wordmark</title>",
        "  <desc id=\"desc\">A light, rounded KUBEONCALL wordmark drawn with a cyan-to-violet gradient on a transparent background.</desc>",
        "  <defs>",
        `    <linearGradient id="wordmark-gradient" x1="50" y1="0" x2="${width - 50}" y2="0" gradientUnits="userSpaceOnUse">`,
        gradientStops,
        "    </linearGradient>",
        "  </defs>",
        `  <g fill="none" stroke="url(#wordmark-gradient)" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round">`,
        paths,
        "  </g>",
        "</svg>",
        "",
    ].join("\n");
}

const lottie = {
    v: "5.12.2",
    fr: frameRate,
    ip: 0,
    op: outPoint,
    w: width,
    h: height,
    nm: "KUBEONCALL natural gradient path reveal",
    ddd: 0,
    assets: [],
    layers: glyphs.map(layerForGlyph).reverse(),
};

mkdirSync(outputDirectory, {recursive: true});
writeFileSync(join(outputDirectory, "wordmark.svg"), buildSvg());
writeFileSync(join(outputDirectory, "wordmark.lottie.json"), `${JSON.stringify(lottie, null, 2)}\n`);

if (playerSceneDirectory) {
    mkdirSync(playerSceneDirectory, {recursive: true});
    writeFileSync(join(playerSceneDirectory, "lottie.json"), `${JSON.stringify(lottie, null, 2)}\n`);
}

console.log(`Generated README wordmark assets in ${outputDirectory}`);
if (playerSceneDirectory) {
    console.log(`Copied Lottie scene to ${playerSceneDirectory}`);
}
