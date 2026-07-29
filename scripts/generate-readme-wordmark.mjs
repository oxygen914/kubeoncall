import {mkdirSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "..");
const outputDirectory = join(repositoryRoot, "assets", "readme", "kubeoncall");
const playerSceneDirectory = process.env.LOTTIE_PLAYER_SCENE_DIR;
const previewFrameDirectory = process.env.WORDMARK_FRAME_DIR;

const width = 1320;
const height = 240;
const frameRate = 60;
const outPoint = 144;
const centerY = 120;
const strokeWidth = 20;
const revealStartFrame = 6;
const glyphStaggerFrames = 7;
const pathStaggerFrames = 3;
const previewFrameStep = 2;

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
        advance: 118,
        paths: [
            line([
                [-36, -70],
                [-36, 70],
            ]),
            line([
                [-31, 4],
                [38, -70],
            ]),
            line([
                [-31, 4],
                [42, 70],
            ]),
        ],
    },
    {
        name: "u",
        advance: 110,
        paths: [
            curve(
                [
                    [-36, -34],
                    [-36, 32],
                    [0, 70],
                    [36, 32],
                    [36, -34],
                ],
                [zero, zero, [-32, 0], [0, 24], zero],
                [zero, [0, 24], [32, 0], zero, zero],
            ),
        ],
    },
    {
        name: "b",
        advance: 112,
        paths: [
            line([
                [-34, -70],
                [-34, 70],
            ]),
            curve(
                [
                    [-30, -18],
                    [4, -34],
                    [38, 18],
                    [4, 70],
                    [-30, 52],
                ],
                [
                    [zero[0], zero[1]],
                    [-18, 0],
                    [0, -28],
                    [18, 0],
                    [0, 18],
                ],
                [
                    [10, -10],
                    [18, 0],
                    [0, 28],
                    [-18, 0],
                    [zero[0], zero[1]],
                ],
            ),
        ],
    },
    {
        name: "e",
        advance: 148,
        paths: [
            curve(
                [
                    [36, 14],
                    [16, -34],
                    [-34, 12],
                    [8, 70],
                    [38, 52],
                ],
                [zero, [14, 0], [0, -28], [-22, 0], [-10, 10]],
                [[-12, -8], [-18, 0], [0, 32], [18, 0], zero],
            ),
            line([
                [-28, 14],
                [30, 14],
            ]),
        ],
    },
    {
        name: "O",
        advance: 132,
        paths: [
            curve(
                [
                    [0, -70],
                    [44, 0],
                    [0, 70],
                    [-44, 0],
                ],
                [
                    [-24, 0],
                    [0, -39],
                    [24, 0],
                    [0, 39],
                ],
                [
                    [24, 0],
                    [0, 39],
                    [-24, 0],
                    [0, -39],
                ],
                true,
            ),
        ],
    },
    {
        name: "n",
        advance: 112,
        paths: [
            line([
                [-34, 70],
                [-34, -32],
            ]),
            curve(
                [
                    [-30, 8],
                    [0, -34],
                    [34, 8],
                    [34, 70],
                ],
                [zero, [-18, 0], [0, -24], zero],
                [[12, -18], [18, 0], [0, 24], zero],
            ),
        ],
    },
    {
        name: "C",
        advance: 128,
        paths: [
            curve(
                [
                    [38, -52],
                    [4, -70],
                    [-44, 0],
                    [4, 70],
                    [38, 52],
                ],
                [zero, [18, 0], [0, -40], [-26, 0], [-10, 10]],
                [[-10, -10], [-26, 0], [0, 40], [18, 0], zero],
            ),
        ],
    },
    {
        name: "a",
        advance: 112,
        paths: [
            curve(
                [
                    [8, -34],
                    [40, 18],
                    [8, 70],
                    [-34, 18],
                    [8, -34],
                ],
                [
                    [-18, 0],
                    [0, -28],
                    [18, 0],
                    [0, 28],
                    [-18, 0],
                ],
                [
                    [18, 0],
                    [0, 28],
                    [-18, 0],
                    [0, -28],
                    [18, 0],
                ],
                true,
            ),
            line([
                [40, -34],
                [40, 70],
            ]),
        ],
    },
    {
        name: "l",
        advance: 68,
        paths: [
            curve(
                [
                    [-8, -70],
                    [-8, 52],
                    [12, 70],
                ],
                [zero, zero, [-12, 0]],
                [zero, [0, 12], zero],
            ),
        ],
    },
    {
        name: "l",
        advance: 68,
        paths: [
            curve(
                [
                    [-8, -70],
                    [-8, 52],
                    [12, 70],
                ],
                [zero, zero, [-12, 0]],
                [zero, [0, 12], zero],
            ),
        ],
    },
];

const palette = [
    {at: 0, color: "#1689F7"},
    {at: 0.45, color: "#536BEA"},
    {at: 0.72, color: "#7958E8"},
    {at: 1, color: "#B94BEA"},
];

const totalAdvance = glyphs.reduce((sum, glyph) => sum + glyph.advance, 0);
let glyphCursor = (width - totalAdvance) / 2;
const glyphPositions = glyphs.map((glyph) => {
    const position = glyphCursor + glyph.advance / 2;
    glyphCursor += glyph.advance;
    return position;
});

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
        .map((value) =>
            Math.round(value * 255)
                .toString(16)
                .padStart(2, "0"),
        )
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
    return leftRgb.map(
        (value, index) => value + (rightRgb[index] - value) * amount,
    );
}

function pathLength(path) {
    let length = 0;
    const segmentCount = path.c ? path.v.length : path.v.length - 1;
    for (let segmentIndex = 0; segmentIndex < segmentCount; segmentIndex += 1) {
        const nextIndex = (segmentIndex + 1) % path.v.length;
        const start = path.v[segmentIndex];
        const end = path.v[nextIndex];
        const control1 = [
            start[0] + path.o[segmentIndex][0],
            start[1] + path.o[segmentIndex][1],
        ];
        const control2 = [
            end[0] + path.i[nextIndex][0],
            end[1] + path.i[nextIndex][1],
        ];
        let previous = start;
        for (let sample = 1; sample <= 20; sample += 1) {
            const time = sample / 20;
            const inverse = 1 - time;
            const point = [0, 1].map(
                (axis) =>
                    inverse ** 3 * start[axis] +
                    3 * inverse ** 2 * time * control1[axis] +
                    3 * inverse * time ** 2 * control2[axis] +
                    time ** 3 * end[axis],
            );
            length += Math.hypot(
                point[0] - previous[0],
                point[1] - previous[1],
            );
            previous = point;
        }
    }
    return length;
}

function revealKeyframes(start, end) {
    return [
        {
            t: start,
            s: [0],
            e: [100],
            o: {x: [0.2], y: [0.75]},
        },
        {
            t: end,
            s: [100],
            i: {x: [0.34], y: [0.94]},
        },
    ];
}

function timingForPath(path, glyphIndex, pathIndex) {
    const start =
        revealStartFrame +
        glyphIndex * glyphStaggerFrames +
        pathIndex * pathStaggerFrames;
    const duration = Math.max(
        18,
        Math.min(34, Math.round(pathLength(path) * 0.12)),
    );
    return {start, end: start + duration};
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

function gradientStroke(startColor, endColor, advance) {
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
        s: {a: 0, k: [-advance / 2, 0]},
        e: {a: 0, k: [advance / 2, 0]},
        t: 1,
        lc: 2,
        lj: 2,
        ml: 4,
    };
}

function layerForGlyph(glyph, glyphIndex) {
    const colorStart = colorAt(glyphIndex / glyphs.length);
    const colorEnd = colorAt((glyphIndex + 1) / glyphs.length);

    const pathGroups = glyph.paths.map((path, pathIndex) => {
        const {start, end} = timingForPath(path, glyphIndex, pathIndex);
        return {
            ty: "gr",
            nm: `${glyph.name}-stroke-${pathIndex + 1}`,
            it: [
                {
                    ty: "sh",
                    nm: `${glyph.name}-path-${pathIndex + 1}`,
                    ks: {a: 0, k: path},
                },
                gradientStroke(colorStart, colorEnd, glyph.advance),
                {
                    ty: "tm",
                    nm: "natural-path-reveal",
                    s: {a: 0, k: 0},
                    e: {a: 1, k: revealKeyframes(start, end)},
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
            p: {a: 0, k: [glyphPositions[glyphIndex], centerY, 0]},
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
    const point = ([x, y]) =>
        `${formatNumber(x + offsetX)} ${formatNumber(y + offsetY)}`;
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
                    `  <path id="glyph-${glyphIndex + 1}-${glyph.name}-${pathIndex + 1}" d="${svgPathData(path, glyphPositions[glyphIndex], centerY)}" />`,
            ),
        )
        .join("\n");

    return [
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc">`,
        '  <title id="title">KubeOnCall gradient wordmark</title>',
        '  <desc id="desc">A rounded KubeOnCall wordmark drawn with a blue-to-violet gradient on a transparent background.</desc>',
        "  <defs>",
        `    <linearGradient id="wordmark-gradient" x1="${formatNumber((width - totalAdvance) / 2)}" y1="0" x2="${formatNumber((width + totalAdvance) / 2)}" y2="0" gradientUnits="userSpaceOnUse">`,
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

function cubicBezierCoordinate(time, control1, control2) {
    const inverse = 1 - time;
    return (
        3 * inverse * inverse * time * control1 +
        3 * inverse * time * time * control2 +
        time * time * time
    );
}

function revealProgress(frame, start, end) {
    if (frame <= start) {
        return 0;
    }
    if (frame >= end) {
        return 1;
    }

    const target = (frame - start) / (end - start);
    let lower = 0;
    let upper = 1;
    for (let iteration = 0; iteration < 12; iteration += 1) {
        const midpoint = (lower + upper) / 2;
        if (cubicBezierCoordinate(midpoint, 0.2, 0.34) < target) {
            lower = midpoint;
        } else {
            upper = midpoint;
        }
    }
    return cubicBezierCoordinate((lower + upper) / 2, 0.75, 0.94);
}

function buildPreviewSvg(frame) {
    const gradientStops = palette
        .map(
            (stop) =>
                `      <stop offset="${formatNumber(stop.at * 100)}%" stop-color="${stop.color}" />`,
        )
        .join("\n");
    const paths = glyphs
        .flatMap((glyph, glyphIndex) =>
            glyph.paths.map((path, pathIndex) => {
                const {start, end} = timingForPath(path, glyphIndex, pathIndex);
                const progress = revealProgress(frame, start, end);
                const length = pathLength(path);
                return `  <path d="${svgPathData(path, glyphPositions[glyphIndex], centerY)}" stroke-dasharray="${formatNumber(length)}" stroke-dashoffset="${formatNumber(length * (1 - progress))}" opacity="${progress > 0 ? 1 : 0}" />`;
            }),
        )
        .join("\n");

    return [
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}">`,
        "  <defs>",
        `    <linearGradient id="wordmark-gradient" x1="${formatNumber((width - totalAdvance) / 2)}" y1="0" x2="${formatNumber((width + totalAdvance) / 2)}" y2="0" gradientUnits="userSpaceOnUse">`,
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
    nm: "KubeOnCall rounded gradient reveal",
    ddd: 0,
    assets: [],
    layers: glyphs.map(layerForGlyph).reverse(),
};

mkdirSync(outputDirectory, {recursive: true});
writeFileSync(join(outputDirectory, "wordmark.svg"), buildSvg());
writeFileSync(
    join(outputDirectory, "wordmark.lottie.json"),
    `${JSON.stringify(lottie, null, 2)}\n`,
);

if (playerSceneDirectory) {
    mkdirSync(playerSceneDirectory, {recursive: true});
    writeFileSync(
        join(playerSceneDirectory, "lottie.json"),
        `${JSON.stringify(lottie, null, 2)}\n`,
    );
}

if (previewFrameDirectory) {
    mkdirSync(previewFrameDirectory, {recursive: true});
    let previewIndex = 0;
    for (let frame = 0; frame < outPoint; frame += previewFrameStep) {
        writeFileSync(
            join(
                previewFrameDirectory,
                `frame-${String(previewIndex).padStart(3, "0")}.svg`,
            ),
            buildPreviewSvg(frame),
        );
        previewIndex += 1;
    }
}

console.log(`Generated README wordmark assets in ${outputDirectory}`);
if (playerSceneDirectory) {
    console.log(`Copied Lottie scene to ${playerSceneDirectory}`);
}
if (previewFrameDirectory) {
    console.log(`Generated preview frames in ${previewFrameDirectory}`);
}
