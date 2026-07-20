import {mkdirSync, writeFileSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "..");
const outputDirectory = join(repositoryRoot, "assets", "readme", "kubeoncall");
const playerSceneDirectory = process.env.LOTTIE_PLAYER_SCENE_DIR;

const width = 1280;
const height = 240;
const frameRate = 60;
const outPoint = 120;
const letterSpacing = 124;
const firstLetterX = 82;
const centerY = 120;
const barThickness = 16;

const letters = [
    {name: "K", color: "#0E7490", bars: [[-30, -66, -30, 66], [-24, 0, 30, -66], [-24, 0, 30, 66]]},
    {name: "U", color: "#0891B2", bars: [[-30, -66, -30, 58], [30, -66, 30, 58], [-30, 66, 30, 66]]},
    {
        name: "B",
        color: "#0284C7",
        bars: [
            [-30, -66, -30, 66],
            [-30, -66, 18, -66],
            [-30, 0, 18, 0],
            [-30, 66, 18, 66],
            [26, -58, 26, -8],
            [26, 8, 26, 58],
        ],
    },
    {name: "E", color: "#2563EB", bars: [[-30, -66, -30, 66], [-30, -66, 30, -66], [-30, 0, 20, 0], [-30, 66, 30, 66]]},
    {name: "O", color: "#3B82F6", bars: [[-30, -66, 30, -66], [-30, 66, 30, 66], [-30, -58, -30, 58], [30, -58, 30, 58]]},
    {name: "N", color: "#4F46E5", bars: [[-30, -66, -30, 66], [30, -66, 30, 66], [-26, -62, 26, 62]]},
    {name: "C", color: "#6366F1", bars: [[-30, -66, 30, -66], [-30, 66, 30, 66], [-30, -58, -30, 58]]},
    {name: "A", color: "#7C3AED", bars: [[-32, 66, 0, -66], [0, -66, 32, 66], [-18, 18, 18, 18]]},
    {name: "L", color: "#7E22CE", bars: [[-30, -66, -30, 66], [-30, 66, 30, 66]]},
    {name: "L", color: "#6D28D9", bars: [[-30, -66, -30, 66], [-30, 66, 30, 66]]},
];

function barGeometry([x1, y1, x2, y2]) {
    const dx = x2 - x1;
    const dy = y2 - y1;
    return {
        x: (x1 + x2) / 2,
        y: (y1 + y2) / 2,
        length: Math.hypot(dx, dy) + barThickness,
        rotation: (Math.atan2(dy, dx) * 180) / Math.PI,
    };
}

function lottieTransform(position = [0, 0], rotation = 0) {
    return {
        ty: "tr",
        p: {a: 0, k: position},
        a: {a: 0, k: [0, 0]},
        s: {a: 0, k: [100, 100]},
        r: {a: 0, k: rotation},
        o: {a: 0, k: 100},
    };
}

function lottieBar(bar, index) {
    const geometry = barGeometry(bar);
    return {
        ty: "gr",
        nm: `stroke-${index + 1}`,
        it: [
            {
                ty: "rc",
                nm: `bar-${index + 1}`,
                p: {a: 0, k: [0, 0]},
                s: {a: 0, k: [geometry.length, barThickness]},
                r: {a: 0, k: barThickness / 2},
            },
            lottieTransform([geometry.x, geometry.y], geometry.rotation),
        ],
    };
}

function colorToLottie(hex) {
    const value = hex.replace("#", "");
    return [
        Number.parseInt(value.slice(0, 2), 16) / 255,
        Number.parseInt(value.slice(2, 4), 16) / 255,
        Number.parseInt(value.slice(4, 6), 16) / 255,
        1,
    ];
}

function easing(start, end, startValue, endValue, curve = [0.2, 0.75, 0.34, 0.94]) {
    return [
        {
            t: start,
            s: startValue,
            e: endValue,
            o: {x: [curve[0]], y: [curve[1]]},
        },
        {
            t: end,
            s: endValue,
            i: {x: [curve[2]], y: [curve[3]]},
        },
    ];
}

function layerForLetter(letter, index) {
    const start = 4 + index * 5;
    const end = start + 18;
    const x = firstLetterX + index * letterSpacing;
    const isFocalO = index === 4;
    const scale = isFocalO
        ? [
              {
                  t: start,
                  s: [82, 82, 100],
                  e: [103, 103, 100],
                  o: {x: [0.2], y: [0.75]},
              },
              {
                  t: end,
                  s: [103, 103, 100],
                  e: [100, 100, 100],
                  i: {x: [0.34], y: [0.94]},
                  o: {x: [0], y: [0.65]},
              },
              {
                  t: end + 8,
                  s: [100, 100, 100],
                  i: {x: [0.51], y: [0.99]},
              },
          ]
        : easing(start, end + 4, [94, 94, 100], [100, 100, 100], [0, 0.65, 0.51, 0.99]);

    return {
        ddd: 0,
        ind: index + 1,
        ty: 4,
        nm: `glyph-${index + 1}-${letter.name}`,
        sr: 1,
        ks: {
            o: {a: 1, k: easing(start + 2, end - 2, [0], [100])},
            r: {a: 0, k: 0},
            p: {a: 1, k: easing(start, end + 4, [x - 10, centerY + 10, 0], [x, centerY, 0])},
            a: {a: 0, k: [0, 0, 0]},
            s: {a: 1, k: scale},
        },
        ao: 0,
        shapes: [
            {
                ty: "gr",
                nm: `${letter.name}-geometry`,
                it: [
                    ...letter.bars.map(lottieBar),
                    {
                        ty: "fl",
                        nm: `${letter.name}-fill`,
                        c: {a: 0, k: colorToLottie(letter.color)},
                        o: {a: 0, k: 100},
                        r: 1,
                    },
                    {
                        ty: "st",
                        nm: `${letter.name}-edge`,
                        c: {a: 0, k: [0.047, 0.071, 0.133, 1]},
                        o: {a: 0, k: 45},
                        w: {a: 0, k: 2.5},
                        lc: 2,
                        lj: 2,
                    },
                    lottieTransform(),
                ],
            },
        ],
        ip: 0,
        op: outPoint,
        st: 0,
        bm: 0,
    };
}

function svgBar(bar) {
    const geometry = barGeometry(bar);
    return `<rect x="${-geometry.length / 2}" y="${-barThickness / 2}" width="${geometry.length}" height="${barThickness}" rx="${barThickness / 2}" transform="translate(${geometry.x} ${geometry.y}) rotate(${geometry.rotation})" />`;
}

function buildSvg() {
    const groups = letters
        .map((letter, index) => {
            const x = firstLetterX + index * letterSpacing;
            return [
                `  <g id="glyph-${index + 1}-${letter.name}" transform="translate(${x} ${centerY})" fill="${letter.color}" stroke="#0C1222" stroke-opacity=".45" stroke-width="2.5">`,
                ...letter.bars.map((bar) => `    ${svgBar(bar)}`),
                "  </g>",
            ].join("\n");
        })
        .join("\n");

    return [
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc">`,
        "  <title id=\"title\">KUBEONCALL wordmark</title>",
        "  <desc id=\"desc\">A geometric blue, teal, and violet KUBEONCALL wordmark on a transparent background.</desc>",
        groups,
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
    nm: "KUBEONCALL technical wordmark",
    ddd: 0,
    assets: [],
    layers: letters.map(layerForLetter).reverse(),
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
