/*
 * Cena 3D, instrumentos e controles do simulador. Depende de THREE (r128) e de EngineSim (sim.js).
 * Unidades da cena: milímetros. Eixo X = eixo do virabrequim (cilindro 1 em X negativo, lado da correia),
 * Y para cima, Z para a frente (lado do escape).
 */
(function () {
  'use strict';

  const S = window.EngineSim;
  const $ = (id) => document.getElementById(id);
  if (!window.THREE || !S) {
    document.body.insertAdjacentHTML('beforeend', '<div class="fail">Não foi possível carregar o Three.js. Verifique a conexão e recarregue a página.</div>');
    return;
  }

  const { DEG, wrap720, clamp } = S;
  const TAU = Math.PI * 2;
  const reduceMotion = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  const rootCss = getComputedStyle(document.documentElement);
  const tok = (n) => rootCss.getPropertyValue(n).trim();
  const COL = {
    adm: tok('--adm'), comp: tok('--comp'), comb: tok('--comb'), esc: tok('--esc'), accent: tok('--accent'),
    fg: tok('--fg'), muted: tok('--muted'), dim: tok('--dim'), line: tok('--line'), ok: tok('--ok'), bad: tok('--bad'),
  };
  const PHASE_COL = [COL.adm, COL.comp, COL.comb, COL.esc];
  const PHASE_UP = ['ADMISSÃO', 'COMPRESSÃO', 'COMBUSTÃO', 'ESCAPE'];
  const PHASE_SHORT = ['ADM', 'COMP', 'COMB', 'ESC'];
  const fmt = (n, d = 0) => (Number.isFinite(n) ? n : 0).toLocaleString('pt-BR', { minimumFractionDigits: d, maximumFractionDigits: d });
  const fmtX = (s) => s.toLocaleString('pt-BR', { maximumSignificantDigits: 2 }) + '×';

  // ------------------------------------------------------------------ simulação
  const E = new S.Engine();
  E.ignition = true;
  E.omega = 850 * TAU / 60;
  E.pMan = 0.22e5;
  E.advance(3);
  E.t = 0;
  E.events.length = 0;

  const ui = {
    paused: reduceMotion,
    speed: 0.05,
    effective: 0.05,
    mode: 'corte',
    section: 'front',
    follow: 0,
    explode: 0,
    opt: { gases: true, arrows: true, trails: true, phase: true, names: false, rodForce: true, flow: true, pan: true, sound: false },
  };

  // ------------------------------------------------------------------ geometria (mm)
  const G = {
    R: 43, L: 140, pinComp: 31,
    cylX: [-132, -44, 44, 132],
    bore: 40.5, linerR: 43.8,
    deck: 214, roof: 223,
    tilt: 17 * DEG, seatY: 221, seatZ: 15, valveDx: 14, camDist: 147,
    beltX: -205,
  };
  const CAM_Y = G.seatY + G.camDist * Math.cos(G.tilt);
  const CAM_Z = G.seatZ + G.camDist * Math.sin(G.tilt);
  const THROW = S.SPEC.offsets.map((o) => o % 360);
  function pinY(a) { const s = Math.sin(a); return G.R * Math.cos(a) + Math.sqrt(G.L * G.L - G.R * G.R * s * s); }

  // ------------------------------------------------------------------ renderizador
  const canvas = $('gl');
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  } catch (err) {
    document.body.insertAdjacentHTML('beforeend', '<div class="fail">Este navegador não conseguiu iniciar o WebGL, necessário para a cena 3D.</div>');
    return;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.outputEncoding = THREE.sRGBEncoding;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.15;
  renderer.localClippingEnabled = true;
  renderer.setClearColor(0x0d0f11, 1);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(32, 1, 5, 20000);

  (function environment() {
    const env = new THREE.Scene();
    env.add(new THREE.Mesh(new THREE.BoxGeometry(40, 40, 40), new THREE.MeshBasicMaterial({ color: 0x15181c, side: THREE.BackSide })));
    const softbox = (w, h, rgb, pos) => {
      const m = new THREE.MeshBasicMaterial({ side: THREE.DoubleSide });
      m.color.setRGB(rgb[0], rgb[1], rgb[2]);
      const p = new THREE.Mesh(new THREE.PlaneGeometry(w, h), m);
      p.position.set(pos[0], pos[1], pos[2]);
      p.lookAt(0, 0, 0);
      env.add(p);
    };
    softbox(26, 12, [5, 5, 5], [0, 19, 2]);
    softbox(6, 24, [2.6, 2.0, 1.5], [-19, 4, 8]);
    softbox(6, 24, [1.2, 1.5, 2.3], [19, 6, -4]);
    softbox(30, 5, [0.9, 0.9, 0.95], [0, 0, -19]);
    softbox(30, 5, [0.6, 0.6, 0.62], [0, -6, 19]);
    const pm = new THREE.PMREMGenerator(renderer);
    scene.environment = pm.fromScene(env, 0.035).texture;
    pm.dispose();
  })();
  const key = new THREE.DirectionalLight(0xffffff, 1.3);
  key.position.set(-400, 900, 700);
  scene.add(key);
  const fill = new THREE.DirectionalLight(0xa8bcff, 0.35);
  fill.position.set(600, 200, -500);
  scene.add(fill);
  scene.add(new THREE.HemisphereLight(0x8a96a6, 0x22180f, 0.3));

  // ------------------------------------------------------------------ materiais
  const staticMats = [], otherMats = [], capMats = [], capMeshes = [];
  // as cores são escritas em sRGB; o renderizador trabalha em espaço linear
  const lin = (c) => new THREE.Color(c).convertSRGBToLinear();
  const setLin = (color, c) => color.set(c).convertSRGBToLinear();
  function std(color, rough, metal, list, extra) {
    const m = new THREE.MeshStandardMaterial(Object.assign({ roughness: rough, metalness: metal }, extra || {}));
    m.color = lin(color);
    (list || otherMats).push(m);
    return m;
  }
  function capMat(color) {
    const m = new THREE.MeshBasicMaterial({ color: lin(color), side: THREE.BackSide });
    capMats.push(m);
    return m;
  }
  const M = {
    block: std(0xbf3a22, 0.55, 0.12, staticMats),
    liner: std(0x8e959e, 0.32, 0.85, staticMats),
    pan: std(0x3d4249, 0.45, 0.75, staticMats),
    gasket: std(0x2a2c30, 0.7, 0.3, staticMats),
    steel: std(0xc6cbd2, 0.24, 1),
    dark: std(0x5f656d, 0.38, 0.9),
    cam: std(0xaab1b9, 0.22, 1),
    valveIn: std(0xd6cfbd, 0.32, 0.9),
    valveEx: std(0xc9b8a0, 0.36, 0.85),
    spring: std(0x3ba35c, 0.42, 0.35),
    bucket: std(0x9aa1a9, 0.3, 0.95),
    belt: std(0x1e1f22, 0.85, 0.05, otherMats, { side: THREE.DoubleSide }),
    beltMark: std(0xd9d6cf, 0.6, 0),
    pulley: std(0x2b2e33, 0.46, 0.6),
    fly: std(0x60666e, 0.32, 0.95),
    gear: std(0x868c94, 0.28, 1),
    plug: std(0xeeeae2, 0.35, 0),
    brass: std(0xb08d4a, 0.35, 0.9),
    white: std(0xf2f2f2, 0.5, 0),
    manIn: std(0x7aa7e8, 0.3, 0.2, otherMats, { transparent: true, opacity: 0.16, depthWrite: false }),
    manEx: std(0xe0915a, 0.3, 0.3, otherMats, { transparent: true, opacity: 0.12, depthWrite: false }),
  };
  const CAP = { block: capMat(0xdf5f3a), liner: capMat(0xb9bec5), pan: capMat(0x5f656d), gasket: capMat(0x3a3d42) };

  // ------------------------------------------------------------------ utilitários de geometria
  const geoCache = new Map();
  function cylY(rt, rb, h, seg = 32, open = false) {
    const k = `y${rt},${rb},${h},${seg},${open}`;
    if (!geoCache.has(k)) geoCache.set(k, new THREE.CylinderGeometry(rt, rb, h, seg, 1, open));
    return geoCache.get(k);
  }
  function cylX(r, x0, x1, seg = 40) {
    const g = new THREE.CylinderGeometry(r, r, x1 - x0, seg);
    g.rotateZ(Math.PI / 2);
    g.translate((x0 + x1) / 2, 0, 0);
    return g;
  }
  function rectShape(x0, y0, x1, y1) {
    const s = new THREE.Shape();
    s.moveTo(x0, y0); s.lineTo(x1, y0); s.lineTo(x1, y1); s.lineTo(x0, y1); s.closePath();
    return s;
  }
  function circlePath(cx, cy, r, Cls) { const p = new (Cls || THREE.Path)(); p.absarc(cx, cy, r, 0, TAU, false); return p; }
  function ringShape(R, r) { const s = circlePath(0, 0, R, THREE.Shape); if (r) s.holes.push(circlePath(0, 0, r)); return s; }
  // contorno no plano (z, y) → forma; o eixo x da forma é −z (ver extrudeX)
  function zyShape(pts) {
    const s = new THREE.Shape();
    pts.forEach((p, i) => (i ? s.lineTo(-p[0], p[1]) : s.moveTo(-p[0], p[1])));
    s.closePath();
    return s;
  }
  // forma (x, −z) extrudada ao longo de Y
  function extrudeY(shape, y0, y1, opt) {
    const g = new THREE.ExtrudeGeometry(shape, Object.assign({ depth: y1 - y0, bevelEnabled: false, curveSegments: 40 }, opt));
    g.rotateX(-Math.PI / 2);
    g.translate(0, y0, 0);
    return g;
  }
  // forma (−z, y) extrudada ao longo de X
  function extrudeX(shape, x0, x1, opt) {
    const g = new THREE.ExtrudeGeometry(shape, Object.assign({ depth: x1 - x0, bevelEnabled: false, curveSegments: 40 }, opt));
    g.rotateY(Math.PI / 2);
    g.translate(x0, 0, 0);
    return g;
  }
  function gearShape(R, teeth, depth, holes) {
    const s = new THREE.Shape();
    const p = TAU / teeth;
    for (let k = 0; k < teeth; k++) {
      const a = k * p;
      const pts = [[R - depth, a], [R, a + 0.28 * p], [R, a + 0.52 * p], [R - depth, a + 0.8 * p]];
      pts.forEach(([rr, aa], j) => {
        const x = rr * Math.cos(aa), y = rr * Math.sin(aa);
        if (k === 0 && j === 0) s.moveTo(x, y); else s.lineTo(x, y);
      });
    }
    s.closePath();
    (holes || []).forEach((h) => s.holes.push(circlePath(h[0], h[1], h[2])));
    return s;
  }

  const pickables = [];
  function mesh(geo, mat, part, parent, cap, data) {
    const m = new THREE.Mesh(geo, mat);
    if (part) { m.userData.part = part; pickables.push(m); }
    if (data) Object.assign(m.userData, data);
    if (cap) { const c = new THREE.Mesh(geo, cap); m.add(c); capMeshes.push(c); }
    if (parent) parent.add(m);
    return m;
  }

  const engine = new THREE.Group();
  scene.add(engine);

  // ------------------------------------------------------------------ bloco
  const blockGroup = new THREE.Group();
  engine.add(blockGroup);
  {
    const s = rectShape(-184, -64, 184, 64);
    G.cylX.forEach((x) => s.holes.push(circlePath(x, 0, G.linerR + 0.2)));
    mesh(extrudeY(s, 60, G.deck), M.block, 'bloco', blockGroup, CAP.block);
    G.cylX.forEach((x, i) => {
      const g = extrudeY(ringShape(G.linerR, G.bore), 56, G.deck);
      g.translate(x, 0, 0);
      mesh(g, M.liner, 'camisa', blockGroup, CAP.liner, { cyl: i });
    });
    const left = [[-90, -40], [-90, 70], [-64, 70], [-64, 60], [-80, 60], [-80, -40]];
    const right = left.map(([z, y]) => [-z, y]);
    mesh(extrudeX(zyShape(left), -184, 184), M.block, 'bloco', blockGroup, CAP.block);
    mesh(extrudeX(zyShape(right), -184, 184), M.block, 'bloco', blockGroup, CAP.block);
    [-88, 0, 88].forEach((x) => {
      const s2 = rectShape(-80, -39.5, 80, 60);
      s2.holes.push(circlePath(0, 0, 27));
      mesh(extrudeX(s2, x - 8, x + 8), M.block, 'bloco', blockGroup, CAP.block);
    });
    [[-192, -176], [176, 192]].forEach(([a, b]) => {
      const s3 = rectShape(-89, -39.5, 89, 70);
      s3.holes.push(circlePath(0, 0, 27));
      mesh(extrudeX(s3, a, b), M.block, 'bloco', blockGroup, CAP.block);
    });
  }

  // ------------------------------------------------------------------ cabeçote
  const headGroup = new THREE.Group();
  engine.add(headGroup);
  {
    const gs = rectShape(-184, -64, 184, 64);
    G.cylX.forEach((x) => gs.holes.push(circlePath(x, 0, G.bore)));
    mesh(extrudeY(gs, G.deck, G.deck + 1.4), M.gasket, 'cabecote', headGroup, CAP.gasket);
    const cs = rectShape(-184, -66, 184, 66);
    G.cylX.forEach((x) => cs.holes.push(circlePath(x, 0, G.bore)));
    mesh(extrudeY(cs, G.deck + 1.4, G.roof), M.block, 'cabecote', headGroup, CAP.block);
    mesh(extrudeY(rectShape(-184, -70, 184, 70), G.roof, 258), M.block, 'cabecote', headGroup, CAP.block);
    [-176, -88, 0, 88, 176].forEach((x) => {
      [-1, 1].forEach((sg) => {
        const zc = sg * CAM_Z;
        const t = zyShape([[zc - 22, 258], [zc + 22, 258], [zc + 22, CAM_Y + 20], [zc - 22, CAM_Y + 20]]);
        t.holes.push(circlePath(-zc, CAM_Y, 13.6));
        mesh(extrudeX(t, x - 7, x + 7), M.block, 'cabecote', headGroup, CAP.block);
      });
    });
  }

  // velas
  const flashes = [];
  G.cylX.forEach((x, i) => {
    const plug = new THREE.Group();
    plug.position.set(x, 0, 0);
    headGroup.add(plug);
    mesh(cylY(9, 9, 12, 6), M.steel, 'vela', plug, null, { cyl: i }).position.y = 264;
    mesh(cylY(5.2, 6.6, 46, 20), M.plug, 'vela', plug, null, { cyl: i }).position.y = 293;
    mesh(cylY(3, 3, 10, 12), M.steel, 'vela', plug, null, { cyl: i }).position.y = 321;
    const fl = new THREE.Mesh(new THREE.SphereGeometry(10, 16, 12), new THREE.MeshBasicMaterial({ color: lin(0xffd58a), transparent: true, opacity: 0.95, blending: THREE.AdditiveBlending, depthWrite: false }));
    fl.position.set(x, G.roof - 4, 0);
    fl.visible = false;
    headGroup.add(fl);
    flashes.push(fl);
  });

  // ------------------------------------------------------------------ válvulas e comandos
  class Helix extends THREE.Curve {
    constructor(R, turns, H) { super(); this.R = R; this.turns = turns; this.H = H; }
    getPoint(t, target) {
      const v = target || new THREE.Vector3();
      const a = t * this.turns * TAU;
      return v.set(this.R * Math.cos(a), t * this.H, this.R * Math.sin(a));
    }
  }
  const SPRING_H = 53;
  const springGeo = new THREE.TubeGeometry(new Helix(11, 6.5, SPRING_H), 160, 1.9, 6, false);

  const valves = [];
  G.cylX.forEach((x, c) => {
    ['in', 'ex'].forEach((side) => {
      const sg = side === 'in' ? -1 : 1;
      [-G.valveDx, G.valveDx].forEach((dx) => {
        const g = new THREE.Group();
        g.position.set(x + dx, G.seatY, sg * G.seatZ);
        g.rotation.x = sg * G.tilt;
        headGroup.add(g);
        const mv = new THREE.Group();
        g.add(mv);
        const part = side === 'in' ? 'valvula_in' : 'valvula_ex';
        const mat = side === 'in' ? M.valveIn : M.valveEx;
        const rh = side === 'in' ? 13 : 11.5;
        const d = { cyl: c, side };
        mesh(cylY(rh, rh, 2.5, 28), mat, part, mv, null, d).position.y = 1.25;
        mesh(cylY(3.6, rh - 1.5, 8, 28), mat, part, mv, null, d).position.y = 6.5;
        mesh(cylY(3, 3, 96, 12), mat, part, mv, null, d).position.y = 58;
        mesh(cylY(9.5, 7, 4, 24), M.steel, 'mola', mv, null, d).position.y = 95;
        mesh(cylY(13, 13, 24, 28), M.bucket, 'tucho', mv, null, d).position.y = 117;
        const sp = mesh(springGeo, M.spring, 'mola', g, null, d);
        sp.position.y = 40;
        valves.push({ g, mv, sp, c, side, dx });
      });
    });
  });

  function lobeShape(noseDeg) {
    const s = new THREE.Shape();
    const N = 144;
    for (let k = 0; k < N; k++) {
      const phi = (k / N) * 360;
      const rel = ((phi + 180) % 360) - 180;
      const u = (rel + 60) / 120;
      const lift = u > 0 && u < 1 ? Math.pow(Math.sin(Math.PI * u), 1.5) : 0;
      const rr = 17 + 9 * lift;
      const a = (noseDeg + phi) * DEG;
      const sx = -rr * Math.sin(a), sy = rr * Math.cos(a);
      if (k) s.lineTo(sx, sy); else s.moveTo(sx, sy);
    }
    s.closePath();
    return s;
  }
  const camGroup = new THREE.Group();
  headGroup.add(camGroup);
  const cams = {};
  ['in', 'ex'].forEach((side) => {
    const sg = side === 'in' ? -1 : 1;
    const g = new THREE.Group();
    g.position.set(0, CAM_Y, sg * CAM_Z);
    camGroup.add(g);
    const part = side === 'in' ? 'comando_in' : 'comando_ex';
    mesh(cylX(13, G.beltX, 184, 28), M.cam, part, g);
    const peak = side === 'in' ? 110 : 610;
    G.cylX.forEach((x, c) => {
      const nose = side === 'in'
        ? 180 - 17 - (peak - S.SPEC.offsets[c]) / 2
        : 180 + 17 - (peak - S.SPEC.offsets[c]) / 2;
      const geo = extrudeX(lobeShape(nose), -7, 7);
      [-G.valveDx, G.valveDx].forEach((dx) => {
        const m = mesh(geo, M.cam, part, g, null, { cyl: c });
        m.position.x = x + dx;
      });
    });
    const holes = [0, 1, 2, 3, 4].map((k) => [28 * Math.cos(k * TAU / 5), 28 * Math.sin(k * TAU / 5), 8.5]);
    mesh(extrudeX(gearShape(48, 38, 3.6, holes), G.beltX - 9, G.beltX + 9), M.pulley, 'polia_comando', g, null, { drive: 0.5, side });
    mesh(cylX(18, G.beltX + 9, G.beltX + 16, 24), M.gear, 'polia_comando', g, null, { drive: 0.5, side });
    cams[side] = g;
  });

  // ------------------------------------------------------------------ virabrequim, volante e polias
  const crank = new THREE.Group();
  engine.add(crank);
  {
    const webShape = new THREE.Shape();
    webShape.moveTo(28, G.R);
    webShape.absarc(0, G.R, 28, 0, Math.PI, false);
    webShape.lineTo(-34, 0);
    webShape.lineTo(66 * Math.cos(215 * DEG), 66 * Math.sin(215 * DEG));
    webShape.absarc(0, 0, 66, 215 * DEG, 325 * DEG, false);
    webShape.lineTo(34, 0);
    webShape.closePath();
    const bevel = { bevelEnabled: true, bevelThickness: 1.6, bevelSize: 1.6, bevelSegments: 2 };
    const ends = [-192, ...G.cylX.flatMap((x) => [x - 26, x + 26]), 192];
    for (let k = 0; k < ends.length; k += 2) mesh(cylX(26, ends[k], ends[k + 1]), M.steel, 'virabrequim', crank);
    mesh(cylX(18, -262, -192, 32), M.steel, 'virabrequim', crank);
    mesh(cylX(42, 192, 200), M.steel, 'virabrequim', crank);
    G.cylX.forEach((x, c) => {
      const a = THROW[c] * DEG;
      const pin = mesh(cylX(22, x - 12, x + 12), M.steel, 'virabrequim', crank, null, { cyl: c });
      pin.position.set(0, G.R * Math.cos(a), G.R * Math.sin(a));
      [[x - 24.4, x - 13.6], [x + 13.6, x + 24.4]].forEach(([a0, a1]) => {
        const w = mesh(extrudeX(webShape, a0, a1, bevel), M.steel, 'contrapeso', crank, null, { cyl: c });
        w.rotation.x = a;
      });
    });
  }
  const noseGroup = new THREE.Group();
  crank.add(noseGroup);
  {
    mesh(extrudeX(gearShape(24, 19, 3), G.beltX - 9, G.beltX + 9), M.gear, 'engrenagem', noseGroup, null, { drive: 1 });
    const prof = [new THREE.Vector2(18, 0), new THREE.Vector2(68, 0), new THREE.Vector2(68, 3)];
    for (let k = 0; k < 7; k++) {
      prof.push(new THREE.Vector2(64, 4 + k * 2.8), new THREE.Vector2(67, 5.4 + k * 2.8));
    }
    prof.push(new THREE.Vector2(68, 24), new THREE.Vector2(68, 27), new THREE.Vector2(40, 27), new THREE.Vector2(40, 31), new THREE.Vector2(18, 31));
    const lg = new THREE.LatheGeometry(prof, 72);
    lg.rotateZ(-Math.PI / 2);
    lg.translate(-262, 0, 0);
    mesh(lg, M.pulley, 'polia', noseGroup, null, { drive: 1 });
    const face = extrudeX(ringShape(60, 20), -264, -262);
    mesh(face, M.dark, 'polia', noseGroup, null, { drive: 1 });
    const mark = new THREE.Mesh(new THREE.BoxGeometry(2, 14, 4), M.white);
    mark.position.set(-265, 54, 0);
    noseGroup.add(mark);
  }
  const flyGroup = new THREE.Group();
  crank.add(flyGroup);
  {
    const holes = [0, 1, 2, 3, 4, 5].map((k) => [86 * Math.cos(k * TAU / 6 + 0.5), 86 * Math.sin(k * TAU / 6 + 0.5), 11]);
    const disc = circlePath(0, 0, 128, THREE.Shape);
    holes.forEach((h) => disc.holes.push(circlePath(h[0], h[1], h[2])));
    mesh(extrudeX(disc, 200, 216), M.fly, 'volante', flyGroup, null, { drive: 1 });
    mesh(extrudeX(gearShape(140, 113, 4.5, [[0, 0, 127.5]]), 202, 214), M.gear, 'volante', flyGroup, null, { drive: 1 });
    mesh(cylX(46, 196, 224), M.fly, 'volante', flyGroup, null, { drive: 1 });
    const mark = new THREE.Mesh(new THREE.BoxGeometry(4, 16, 3), M.white);
    mark.position.set(217, 112, 0);
    flyGroup.add(mark);
  }

  // ------------------------------------------------------------------ correia dentada
  const beltGroup = new THREE.Group();
  engine.add(beltGroup);
  const BELT_R = 25.5;
  const pulleys = [
    { z: 0, y: 0, r: BELT_R },
    { z: -CAM_Z, y: CAM_Y, r: 49.5 },
    { z: CAM_Z, y: CAM_Y, r: 49.5 },
    { z: 72, y: 196, r: 17.5, kind: 'tensor' },
    { z: -76, y: 150, r: 27.5, kind: 'bomba' },
  ];
  const belt = (function buildBelt() {
    const pts = [];
    pulleys.forEach((p) => { for (let k = 0; k < 180; k++) { const a = (k / 180) * TAU; pts.push([p.z + p.r * Math.cos(a), p.y + p.r * Math.sin(a)]); } });
    pts.sort((a, b) => (a[0] - b[0]) || (a[1] - b[1]));
    const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    const lower = [], upper = [];
    for (const p of pts) { while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop(); lower.push(p); }
    for (let k = pts.length - 1; k >= 0; k--) { const p = pts[k]; while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop(); upper.push(p); }
    const hull = lower.slice(0, -1).concat(upper.slice(0, -1));
    const cum = [0];
    for (let k = 1; k <= hull.length; k++) {
      const a = hull[k - 1], b = hull[k % hull.length];
      cum.push(cum[k - 1] + Math.hypot(b[0] - a[0], b[1] - a[1]));
    }
    const L = cum[cum.length - 1];
    const N = 900;
    const sz = new Float32Array(N), sy = new Float32Array(N), tz = new Float32Array(N), ty = new Float32Array(N);
    let j = 0;
    for (let k = 0; k < N; k++) {
      const s = (k / N) * L;
      while (cum[j + 1] < s) j++;
      const a = hull[j], b = hull[(j + 1) % hull.length];
      const f = (s - cum[j]) / (cum[j + 1] - cum[j] || 1);
      sz[k] = a[0] + (b[0] - a[0]) * f;
      sy[k] = a[1] + (b[1] - a[1]) * f;
    }
    for (let k = 0; k < N; k++) {
      const a = (k + N - 2) % N, b = (k + 2) % N;
      const dz = sz[b] - sz[a], dy = sy[b] - sy[a], n = Math.hypot(dz, dy) || 1;
      tz[k] = dz / n; ty[k] = dy / n;
    }
    // a normal externa fica à direita da tangente (contorno anti-horário no plano z-y)
    const x0 = G.beltX - 9, x1 = G.beltX + 9;
    const pos = [];
    const quad = (a, b, c, d) => pos.push(...a, ...b, ...c, ...a, ...c, ...d);
    for (let k = 0; k < N; k++) {
      const m = (k + 1) % N;
      const io = (q, off, x) => [x, sy[q] - tz[q] * off, sz[q] + ty[q] * off];
      const i0 = io(k, 0, x0), i1 = io(m, 0, x0), o0 = io(k, 3.4, x0), o1 = io(m, 3.4, x0);
      const i0b = io(k, 0, x1), i1b = io(m, 0, x1), o0b = io(k, 3.4, x1), o1b = io(m, 3.4, x1);
      quad(o0, o1, o1b, o0b);
      quad(i0b, i1b, i1, i0);
      quad(i0, i1, o1, o0);
      quad(o0b, o1b, i1b, i0b);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.computeVertexNormals();
    mesh(g, M.belt, 'correia', beltGroup, null, { drive: 1 });
    const teethN = Math.round(L / 9.525);
    const teeth = new THREE.InstancedMesh(new THREE.BoxGeometry(18, 3.2, 4.6), M.belt, teethN);
    teeth.userData.part = 'correia';
    pickables.push(teeth);
    beltGroup.add(teeth);
    const marks = new THREE.InstancedMesh(new THREE.BoxGeometry(9, 0.8, 16), M.beltMark, 6);
    beltGroup.add(marks);
    return { L, N, sz, sy, tz, ty, teeth, teethN, marks };
  })();
  const beltM = new THREE.Matrix4(), bx = new THREE.Vector3(1, 0, 0), byV = new THREE.Vector3(), bzV = new THREE.Vector3();
  function placeOnBelt(inst, idx, s, off) {
    const f = (((s % belt.L) + belt.L) % belt.L) / belt.L * belt.N;
    const k = Math.floor(f) % belt.N;
    const k2 = (k + 1) % belt.N;
    const t = f - Math.floor(f);
    const z = belt.sz[k] + (belt.sz[k2] - belt.sz[k]) * t;
    const y = belt.sy[k] + (belt.sy[k2] - belt.sy[k]) * t;
    const tz = belt.tz[k], ty = belt.ty[k];
    byV.set(0, -tz, ty);
    bzV.set(0, -ty, -tz);
    beltM.makeBasis(bx, byV, bzV);
    beltM.setPosition(G.beltX, y - tz * off, z + ty * off);
    inst.setMatrixAt(idx, beltM);
  }
  const idlers = pulleys.filter((p) => p.kind).map((p) => {
    const g = new THREE.Group();
    g.position.set(G.beltX, p.y, p.z);
    beltGroup.add(g);
    if (p.kind === 'tensor') {
      mesh(extrudeX(ringShape(17, 4), -9, 9), M.steel, 'tensor', g);
      mesh(extrudeX(ringShape(19, 13), 9, 11), M.dark, 'tensor', g);
      const arm = mesh(new THREE.BoxGeometry(6, 14, 46), M.dark, 'tensor', beltGroup);
      arm.position.set(G.beltX + 14, p.y - 14, p.z - 18);
      arm.rotation.x = 0.6;
    } else {
      mesh(extrudeX(gearShape(27, 22, 2.4, [[0, 0, 8]]), -9, 9), M.pulley, 'bomba', g);
      mesh(cylX(12, 9, 34, 24), M.dark, 'bomba', g);
    }
    return { g, ratio: BELT_R / p.r };
  });

  // ------------------------------------------------------------------ cárter
  const panGroup = new THREE.Group();
  engine.add(panGroup);
  {
    const tub = zyShape([[-90, -41], [-82, -128], [82, -128], [90, -41], [84, -41], [77, -122], [-77, -122], [-84, -41]]);
    mesh(extrudeX(tub, -178, 178), M.pan, 'carter', panGroup, CAP.pan);
    const plate = zyShape([[-90, -41], [-82, -128], [82, -128], [90, -41]]);
    mesh(extrudeX(plate, -184, -178), M.pan, 'carter', panGroup, CAP.pan);
    mesh(extrudeX(plate, 178, 184), M.pan, 'carter', panGroup, CAP.pan);
    const plug = mesh(cylY(8, 8, 8, 6), M.steel, 'carter', panGroup);
    plug.position.set(120, -131, 0);
  }

  // ------------------------------------------------------------------ coletores
  const manGroup = new THREE.Group();
  headGroup.add(manGroup);
  const intakeCurves = [], exhaustCurves = [];
  G.cylX.forEach((x) => {
    intakeCurves.push(new THREE.CatmullRomCurve3([
      new THREE.Vector3(x * 0.9, 352, -205), new THREE.Vector3(x, 330, -165), new THREE.Vector3(x, 285, -118),
      new THREE.Vector3(x, 250, -82), new THREE.Vector3(x, 236, -50), new THREE.Vector3(x, 228, -24),
    ]));
    exhaustCurves.push(new THREE.CatmullRomCurve3([
      new THREE.Vector3(x, 228, 24), new THREE.Vector3(x, 236, 60), new THREE.Vector3(x, 228, 102),
      new THREE.Vector3(x * 0.75, 172, 138), new THREE.Vector3(x * 0.3, 112, 150), new THREE.Vector3(0, 66, 152),
    ]));
  });
  const collectorCurve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(0, 66, 152), new THREE.Vector3(0, -40, 154), new THREE.Vector3(30, -120, 150), new THREE.Vector3(120, -160, 140),
  ]);
  intakeCurves.forEach((cv) => mesh(new THREE.TubeGeometry(cv, 48, 13, 12), M.manIn, 'coletor_in', manGroup));
  exhaustCurves.forEach((cv) => mesh(new THREE.TubeGeometry(cv, 48, 14, 12), M.manEx, 'coletor_ex', manGroup));
  mesh(new THREE.TubeGeometry(collectorCurve, 32, 22, 12), M.manEx, 'coletor_ex', manGroup);
  {
    const plenum = mesh(cylX(38, -172, 178, 32), M.manIn, 'coletor_in', manGroup);
    plenum.position.set(0, 352, -205);
    const body = mesh(cylX(26, 178, 224, 28), M.manIn, 'borboleta', manGroup);
    body.position.set(0, 352, -205);
  }
  const throttlePlate = (function () {
    const g = new THREE.CylinderGeometry(24, 24, 2, 32);
    g.rotateZ(Math.PI / 2);
    const m = mesh(g, M.brass, 'borboleta', manGroup);
    m.position.set(204, 352, -205);
    return m;
  })();
  const dotTex = (function () {
    const c = document.createElement('canvas');
    c.width = c.height = 32;
    const x = c.getContext('2d');
    const gr = x.createRadialGradient(16, 16, 0, 16, 16, 16);
    gr.addColorStop(0, 'rgba(255,255,255,1)');
    gr.addColorStop(0.45, 'rgba(255,255,255,0.7)');
    gr.addColorStop(1, 'rgba(255,255,255,0)');
    x.fillStyle = gr;
    x.fillRect(0, 0, 32, 32);
    return new THREE.CanvasTexture(c);
  })();
  function makeFlow(curve, color, count) {
    const geo = new THREE.BufferGeometry();
    const pos = new Float32Array(count * 3);
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    const mat = new THREE.PointsMaterial({ color: lin(color), size: 9, map: dotTex, transparent: true, opacity: 0.9, depthWrite: false, blending: THREE.AdditiveBlending });
    const pts = new THREE.Points(geo, mat);
    pts.frustumCulled = false;
    manGroup.add(pts);
    const u = new Float32Array(count);
    for (let k = 0; k < count; k++) u[k] = (k + Math.random() * 0.6) / count;
    return { pts, pos, u, curve, count, mat };
  }
  const flowIn = intakeCurves.map((cv) => makeFlow(cv, 0x6fb0ff, 14));
  const flowEx = exhaustCurves.map((cv) => makeFlow(cv, 0xffa24d, 14));
  const flowCol = makeFlow(collectorCurve, 0xff9a40, 16);
  const tmpP = new THREE.Vector3();
  function moveFlow(f, du, strength) {
    const d = clamp(du, -0.08, 0.08);
    for (let k = 0; k < f.count; k++) {
      f.u[k] = ((f.u[k] + d) % 1 + 1) % 1;
      f.curve.getPointAt(f.u[k], tmpP);
      f.pos[k * 3] = tmpP.x; f.pos[k * 3 + 1] = tmpP.y; f.pos[k * 3 + 2] = tmpP.z;
    }
    f.pts.geometry.attributes.position.needsUpdate = true;
    f.mat.opacity = 0.15 + 0.85 * strength;
  }

  // ------------------------------------------------------------------ pistões, bielas, gases, setas
  const cylParts = G.cylX.map((x, c) => {
    const mats = [];
    const own = (m) => { mats.push(m); return m; };
    const pistonMat = own(new THREE.MeshStandardMaterial({ color: lin(0xb4bac2), roughness: 0.3, metalness: 0.8 }));
    const ringMat = own(new THREE.MeshStandardMaterial({ color: lin(0x2c2f34), roughness: 0.5, metalness: 0.6 }));
    const rodMat = own(new THREE.MeshStandardMaterial({ color: lin(0xd0d4da), roughness: 0.3, metalness: 0.9 }));
    const gasMat = own(new THREE.MeshBasicMaterial({ color: lin(0x4f93e6), transparent: true, opacity: 0.42, depthWrite: false }));
    const d = { cyl: c };
    const piston = new THREE.Group();
    engine.add(piston);
    mesh(cylY(40.2, 40.2, 61, 48), pistonMat, 'pistao', piston, null, d).position.y = 0.5;
    [26, 21.5, 17].forEach((y) => { mesh(cylY(40.4, 40.4, 1.6, 48, true), ringMat, 'pistao', piston, null, d).position.y = y; });
    const rod = new THREE.Group();
    engine.add(rod);
    mesh(extrudeX(ringShape(31, 22.4), -11, 11), rodMat, 'biela', rod, null, d);
    mesh(extrudeX(zyShape([[-13, 24], [13, 24], [8, 128], [-8, 128]]), -7, 7), rodMat, 'biela', rod, null, d);
    const se = extrudeX(ringShape(15, 10.6), -11, 11);
    se.translate(0, G.L, 0);
    mesh(se, rodMat, 'biela', rod, null, d);
    [-25, 25].forEach((z) => { const b = mesh(cylY(4, 4, 30, 10), M.dark, 'biela', rod, null, d); b.position.set(0, -14, z); });
    const gas = new THREE.Mesh(cylY(40, 40, 1, 40), gasMat);
    gas.renderOrder = 2;
    engine.add(gas);
    const arrow = makeArrow(0xffffff);
    return { x, c, piston, rod, gas, gasMat, rodMat, pistonMat, mats, arrow, flash: 0 };
  });

  function makeArrow(color) {
    const mat = new THREE.MeshBasicMaterial({ color: lin(color), depthTest: false, transparent: true });
    const g = new THREE.Group();
    const shaft = new THREE.Mesh(cylY(2.6, 2.6, 1, 10), mat);
    const head = new THREE.Mesh(new THREE.ConeGeometry(8, 18, 16), mat);
    head.rotation.x = Math.PI;
    g.add(shaft, head);
    g.renderOrder = 20;
    shaft.renderOrder = head.renderOrder = 20;
    engine.add(g);
    return { g, shaft, head, mat };
  }
  function setArrowDown(a, x, yTip, z, len) {
    a.g.position.set(x, 0, z);
    a.head.position.y = yTip + 9;
    const sl = Math.max(len - 18, 1);
    a.shaft.scale.y = sl;
    a.shaft.position.y = yTip + 18 + sl / 2;
  }

  // seta curva do torque no virabrequim
  class ArcCurve extends THREE.Curve {
    getPoint(t, target) {
      const v = target || new THREE.Vector3();
      const a = t * 300 * DEG;
      return v.set(0, 160 * Math.cos(a), 160 * Math.sin(a));
    }
  }
  const torqueArrow = (function () {
    const mat = new THREE.MeshBasicMaterial({ color: lin(0xf2c46b), transparent: true, opacity: 0.95, side: THREE.DoubleSide });
    const g = new THREE.Group();
    g.position.x = 232;
    const SEG = 72;
    const tube = new THREE.Mesh(new THREE.TubeGeometry(new ArcCurve(), SEG, 3.2, 6, false), mat);
    const head = new THREE.Mesh(new THREE.ConeGeometry(9, 22, 14), mat);
    g.add(tube, head);
    engine.add(g);
    return { g, tube, head, SEG };
  })();
  function setTorqueArrow(T) {
    const f = clamp(Math.abs(T) / 180, 0.04, 1);
    const seg = Math.max(1, Math.round(f * torqueArrow.SEG));
    torqueArrow.tube.geometry.setDrawRange(0, seg * 6 * 6);
    const a = (seg / torqueArrow.SEG) * 300 * DEG;
    torqueArrow.head.position.set(0, 160 * Math.cos(a), 160 * Math.sin(a));
    torqueArrow.head.rotation.set(a + Math.PI / 2, 0, 0);
    torqueArrow.g.scale.z = T >= 0 ? 1 : -1;
  }

  // ------------------------------------------------------------------ trilhas
  const bgCol = lin(0x0d0f11);
  function makeTrail(color, n) {
    const geo = new THREE.BufferGeometry();
    const pos = new Float32Array(n * 3), col = new Float32Array(n * 3);
    const c = lin(color), t = new THREE.Color();
    for (let k = 0; k < n; k++) { t.copy(c).lerp(bgCol, k / n); col[k * 3] = t.r; col[k * 3 + 1] = t.g; col[k * 3 + 2] = t.b; }
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
    geo.setDrawRange(0, 0);
    const line = new THREE.Line(geo, new THREE.LineBasicMaterial({ vertexColors: true, depthTest: false, transparent: true }));
    line.renderOrder = 15;
    line.frustumCulled = false;
    engine.add(line);
    return { line, pos, n, count: 0, last: new THREE.Vector3(1e9, 0, 0) };
  }
  const trails = [makeTrail(0xf2b54a, 360), makeTrail(0x63d0e0, 360), makeTrail(0xffffff, 120)];
  function pushTrail(tr, v) {
    if (v.distanceToSquared(tr.last) < 0.8) return;
    tr.last.copy(v);
    tr.pos.copyWithin(3, 0, (tr.n - 1) * 3);
    tr.pos[0] = v.x; tr.pos[1] = v.y; tr.pos[2] = v.z;
    tr.count = Math.min(tr.n, tr.count + 1);
    tr.line.geometry.setDrawRange(0, tr.count);
    tr.line.geometry.attributes.position.needsUpdate = true;
  }
  function resetTrails() { trails.forEach((t) => { t.count = 0; t.last.set(1e9, 0, 0); t.line.geometry.setDrawRange(0, 0); }); }

  // ------------------------------------------------------------------ corte e raio X
  const clipFront = new THREE.Plane(new THREE.Vector3(0, 0, -1), 0);
  const clipSide = new THREE.Plane(new THREE.Vector3(-1, 0, 0), 0);
  function applyMode() {
    const corte = ui.mode === 'corte', xr = ui.mode === 'raiox';
    const side = corte && ui.section === 'side';
    clipSide.constant = G.cylX[ui.follow];
    const sp = side ? [clipSide] : corte ? [clipFront] : [];
    for (const m of staticMats) {
      m.clippingPlanes = sp;
      m.transparent = xr;
      m.opacity = xr ? 0.12 : 1;
      m.depthWrite = !xr;
      m.needsUpdate = true;
    }
    for (const m of capMats) { m.clippingPlanes = sp; m.needsUpdate = true; }
    capMeshes.forEach((c) => { c.visible = corte; });
    M.manIn.visible = M.manEx.visible = !side;
    const so = side ? [clipSide] : [];
    for (const m of otherMats) { m.clippingPlanes = so; m.needsUpdate = true; }
    cylParts.forEach((cp) => {
      const hide = side && cp.x > G.cylX[ui.follow];
      cp.piston.visible = cp.rod.visible = !hide;
      cp.mats.forEach((m) => { m.clippingPlanes = side && cp.c !== ui.follow ? [clipSide] : []; m.needsUpdate = true; });
    });
    document.querySelectorAll('#modeSeg .btn').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mode === ui.mode)));
  }

  // ------------------------------------------------------------------ pose a cada quadro
  const vA = new THREE.Vector3(), vB = new THREE.Vector3();
  const colTmp = new THREE.Color();
  const ROD_IDLE = lin(0xd0d4da), ROD_COMP = lin(0xff4a26), ROD_TENS = lin(0x3d8bff);
  const GAS = PHASE_COL.map((c) => lin(c));
  const HOT = lin(0xffe2a6);
  let lastTheta = E.thetaCum;

  function updatePose() {
    const th = E.thetaCum * DEG;
    const dTh = (E.thetaCum - lastTheta) * DEG;
    lastTheta = E.thetaCum;
    crank.rotation.x = th;
    cams.in.rotation.x = th / 2;
    cams.ex.rotation.x = th / 2;

    cylParts.forEach((cp) => {
      const c = E.cyl[cp.c];
      const a = th + S.SPEC.offsets[cp.c] * DEG;
      const yp = pinY(a);
      const py = G.R * Math.cos(a), pz = G.R * Math.sin(a);
      cp.piston.position.set(cp.x, yp, 0);
      cp.rod.position.set(cp.x, py, pz);
      cp.rod.rotation.x = Math.atan2(-pz, yp - py);
      const crown = yp + G.pinComp;
      const h = Math.max(0.5, G.roof - crown);
      cp.gas.position.set(cp.x, crown + h / 2, 0);
      cp.gas.scale.y = h;
      cp.gas.visible = ui.opt.gases && cp.piston.visible;

      const psi = c.psi;
      const burning = c.burnStart !== null && c.xb > 0.002 && c.xb < 0.999;
      let ph = S.phaseOf(psi);
      if (burning && ph === 1) ph = 2;
      if (ph === 2) {
        const hot = clamp((c.T - 900) / 1700, 0, 1);
        colTmp.copy(GAS[2]).multiplyScalar(0.55 + 0.45 * clamp(c.T / 1400, 0, 1)).lerp(HOT, hot * 0.7);
        cp.gasMat.opacity = 0.55 + 0.25 * hot;
      } else {
        colTmp.copy(GAS[ph]);
        cp.gasMat.opacity = ph === 3 ? 0.4 : 0.5;
      }
      cp.gasMat.color.copy(colTmp);

      if (c.burnStart !== null && c.xb < 0.12 && E.ignition) cp.flash = 1;
      else cp.flash = Math.max(0, cp.flash - Math.abs(dTh) * 3);
      flashes[cp.c].visible = cp.flash > 0.02;
      flashes[cp.c].scale.setScalar(0.4 + cp.flash);
      flashes[cp.c].material.opacity = cp.flash;

      if (ui.opt.rodForce) {
        const F = c.Frod;
        if (F >= 0) cp.rodMat.color.copy(ROD_IDLE).lerp(ROD_COMP, clamp(F / 22000, 0, 1));
        else cp.rodMat.color.copy(ROD_IDLE).lerp(ROD_TENS, clamp(-F / 7000, 0, 1));
      } else cp.rodMat.color.copy(ROD_IDLE);

      const F = c.F;
      const show = ui.opt.arrows && Math.abs(F) > 150 && cp.piston.visible;
      cp.arrow.g.visible = show;
      if (show) {
        const len = clamp(22 + Math.abs(F) / 260, 24, 150);
        setLin(cp.arrow.mat.color, F > 0 ? PHASE_COL[ph] : COL.adm);
        if (F > 0) setArrowDown(cp.arrow, cp.x, crown, 50, len);
        else {
          setArrowDown(cp.arrow, cp.x, crown + len, 50, len);
          cp.arrow.head.position.y = crown + len - 9;
          cp.arrow.head.rotation.x = 0;
          cp.arrow.shaft.position.y = crown + (len - 18) / 2;
        }
        if (F > 0) cp.arrow.head.rotation.x = Math.PI;
      }
    });

    valves.forEach((v) => {
      const psi = E.psi(v.c);
      const lift = v.side === 'in' ? S.liftIn(psi) * S.SPEC.liftIn : S.liftEx(psi) * S.SPEC.liftEx;
      v.mv.position.y = -lift;
      v.sp.scale.y = (SPRING_H - lift) / SPRING_H;
    });

    const s0 = -BELT_R * th;
    for (let k = 0; k < belt.teethN; k++) placeOnBelt(belt.teeth, k, s0 + k * (belt.L / belt.teethN), -1.8);
    belt.teeth.instanceMatrix.needsUpdate = true;
    for (let k = 0; k < 6; k++) placeOnBelt(belt.marks, k, s0 + k * (belt.L / 6) + 4, 3.6);
    belt.marks.instanceMatrix.needsUpdate = true;
    idlers.forEach((p) => { p.g.rotation.x = th * p.ratio; });
    throttlePlate.rotation.y = (8 + 78 * E.throttle) * DEG;

    // fluxo nos coletores
    const flowOn = ui.opt.flow;
    const sideCut = ui.mode === 'corte' && ui.section === 'side';
    let exSum = 0;
    for (let c = 0; c < 4; c++) {
      const psi = E.psi(c);
      const li = S.liftIn(psi), le = S.liftEx(psi);
      const vis = flowOn && (!sideCut || c === ui.follow);
      flowIn[c].pts.visible = flowEx[c].pts.visible = vis;
      if (vis) {
        moveFlow(flowIn[c], li * dTh * 0.05, li);
        moveFlow(flowEx[c], le * dTh * 0.05, le);
      }
      exSum += le;
    }
    flowCol.pts.visible = flowOn && !sideCut;
    if (flowCol.pts.visible) moveFlow(flowCol, Math.min(1, exSum) * dTh * 0.04, Math.min(1, 0.3 + exSum));

    // seta de torque
    torqueArrow.g.visible = ui.opt.arrows && !sideCut;
    if (ui.opt.arrows) setTorqueArrow(E.tGas + E.tIn);

    // trilhas do cilindro acompanhado
    const tv = ui.opt.trails;
    trails.forEach((t) => { t.line.visible = tv; });
    if (tv) {
      const cp = cylParts[ui.follow];
      const a = th + S.SPEC.offsets[ui.follow] * DEG;
      vA.set(cp.x, G.R * Math.cos(a), G.R * Math.sin(a));
      pushTrail(trails[0], vA);
      vB.set(cp.x, pinY(a), 0);
      pushTrail(trails[2], vB);
      vA.lerp(vB, 0.5);
      pushTrail(trails[1], vA);
    }
  }

  function applyExplode() {
    const k = ui.explode;
    headGroup.position.y = 170 * k;
    camGroup.position.y = 90 * k;
    panGroup.position.y = -150 * k;
    flyGroup.position.x = 120 * k;
    noseGroup.position.x = -80 * k;
    beltGroup.visible = k < 0.03;
  }

  // ------------------------------------------------------------------ câmera
  const view = { target: new THREE.Vector3(), radius: 1000, theta: 0, phi: 1.2 };
  let tween = null;
  let fitK = 1; // afasta a câmera em telas estreitas para o motor caber na largura
  function applyView() {
    const sp = Math.sin(view.phi);
    const r = view.radius * fitK;
    camera.position.set(
      view.target.x + r * sp * Math.sin(view.theta),
      view.target.y + r * Math.cos(view.phi),
      view.target.z + r * sp * Math.cos(view.theta));
    camera.lookAt(view.target);
  }
  function viewFrom(pos, target) {
    const o = new THREE.Vector3().subVectors(pos, target);
    const r = o.length();
    return { target: target.clone(), radius: r, theta: Math.atan2(o.x, o.z), phi: Math.acos(clamp(o.y / r, -1, 1)) };
  }
  function flyTo(pos, target) {
    const to = viewFrom(pos, target);
    let d = to.theta - view.theta;
    d = ((d + Math.PI) % TAU + TAU) % TAU - Math.PI;
    to.theta = view.theta + d;
    if (reduceMotion) { Object.assign(view, to); applyView(); return; }
    tween = { from: { target: view.target.clone(), radius: view.radius, theta: view.theta, phi: view.phi }, to, t0: performance.now(), ms: 900 };
  }
  function stepTween(now) {
    if (!tween) return;
    let k = clamp((now - tween.t0) / tween.ms, 0, 1);
    const e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
    view.target.lerpVectors(tween.from.target, tween.to.target, e);
    view.radius = tween.from.radius + (tween.to.radius - tween.from.radius) * e;
    view.theta = tween.from.theta + (tween.to.theta - tween.from.theta) * e;
    view.phi = tween.from.phi + (tween.to.phi - tween.from.phi) * e;
    if (k >= 1) tween = null;
  }
  const V3 = (x, y, z) => new THREE.Vector3(x, y, z);
  const CAMS = {
    geral: () => [V3(-420, 420, 1420), V3(-10, 130, 0)],
    lateral: () => { const x = G.cylX[ui.follow]; return [V3(x + 1350, 330, 440), V3(x, 150, 0)]; },
    cabecote: () => [V3(-200, 860, 720), V3(0, 290, 0)],
    correia: () => [V3(-1250, 420, 640), V3(-205, 165, 0)],
    baixo: () => [V3(300, -820, 860), V3(0, 20, 0)],
  };
  let camPreset = 'geral';
  function setCamera(name, instant) {
    camPreset = name;
    const wasSide = ui.section === 'side';
    ui.section = name === 'lateral' ? 'side' : 'front';
    if (name === 'lateral' && ui.mode !== 'corte') ui.mode = 'corte';
    if (name === 'baixo' && ui.opt.pan) { ui.opt.pan = false; $('oPan').checked = false; panGroup.visible = false; }
    if (wasSide !== (ui.section === 'side') || name === 'lateral') applyMode();
    const [p, t] = CAMS[name]();
    if (instant) { Object.assign(view, viewFrom(p, t)); applyView(); } else flyTo(p, t);
    document.querySelectorAll('[data-cam]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.cam === name)));
    resetTrails();
  }

  // ------------------------------------------------------------------ seleção, giro à mão, órbita
  const raycaster = new THREE.Raycaster();
  const ndc = new THREE.Vector2();
  function shown(o) { for (let p = o; p; p = p.parent) if (!p.visible) return false; return true; }
  function pick(cx, cy) {
    const r = canvas.getBoundingClientRect();
    ndc.set(((cx - r.left) / r.width) * 2 - 1, -((cy - r.top) / r.height) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    const hits = raycaster.intersectObjects(pickables, false);
    for (const h of hits) {
      if (!shown(h.object)) continue;
      const planes = h.object.material.clippingPlanes;
      if (planes && planes.some((pl) => pl.distanceToPoint(h.point) < 0)) continue;
      if (h.object.material.transparent && h.object.material.opacity < 0.2 && !h.object.userData.drive) continue;
      return h;
    }
    return null;
  }

  const pointers = new Map();
  let drag = null;
  let hover = null;
  const rayPlane = new THREE.Plane();
  const hitPt = new THREE.Vector3();
  function driveAngle(cx, cy, d) {
    const r = canvas.getBoundingClientRect();
    ndc.set(((cx - r.left) / r.width) * 2 - 1, -((cy - r.top) / r.height) * 2 + 1);
    raycaster.setFromCamera(ndc, camera);
    rayPlane.set(new THREE.Vector3(1, 0, 0), -d.x);
    if (!raycaster.ray.intersectPlane(rayPlane, hitPt)) return null;
    return Math.atan2(hitPt.z - d.cz, hitPt.y - d.cy);
  }
  function wrapPi(a) { return ((a + Math.PI) % TAU + TAU) % TAU - Math.PI; }

  canvas.addEventListener('contextmenu', (e) => e.preventDefault());
  canvas.addEventListener('pointerdown', (e) => {
    canvas.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    hideTooltip();
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      drag = { kind: 'pinch', dist: Math.hypot(a.x - b.x, a.y - b.y), mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } };
      if (E.hand) E.hand = null;
      return;
    }
    tween = null;
    const h = e.button === 0 && !e.shiftKey ? pick(e.clientX, e.clientY) : null;
    if (h && h.object.userData.drive) {
      const ratio = h.object.userData.drive;
      const wp = new THREE.Vector3();
      h.object.getWorldPosition(wp);
      const isCam = ratio !== 1;
      const camWorld = new THREE.Vector3();
      if (isCam) cams[h.object.userData.side].getWorldPosition(camWorld);
      const d = { x: h.point.x, cy: isCam ? camWorld.y : 0, cz: isCam ? camWorld.z : 0, ratio };
      const camDir = new THREE.Vector3();
      camera.getWorldDirection(camDir);
      const edgeOn = Math.abs(camDir.x) < 0.22;
      const a0 = edgeOn ? null : driveAngle(e.clientX, e.clientY, d);
      drag = { kind: 'hand', d, edgeOn, prevA: a0, acc: 0, y: e.clientY, base: E.thetaCum * DEG, sgnZ: camDir.z < 0 ? 1 : -1 };
      E.hand = { target: drag.base };
      canvas.classList.add('dragging');
      return;
    }
    drag = { kind: e.button === 2 || e.shiftKey ? 'pan' : 'orbit', x: e.clientX, y: e.clientY };
    canvas.classList.add('dragging');
  });
  canvas.addEventListener('pointermove', (e) => {
    const p = pointers.get(e.pointerId);
    if (!drag) { queueHover(e.clientX, e.clientY); return; }
    if (p) { p.x = e.clientX; p.y = e.clientY; }
    if (drag.kind === 'pinch' && pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y);
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      view.radius = clamp(view.radius * drag.dist / Math.max(dist, 1), 220, 4000);
      panBy(mid.x - drag.mid.x, mid.y - drag.mid.y);
      drag.dist = dist; drag.mid = mid;
    } else if (drag.kind === 'orbit') {
      view.theta -= (e.clientX - drag.x) * 0.006;
      view.phi = clamp(view.phi - (e.clientY - drag.y) * 0.006, 0.08, Math.PI - 0.08);
      drag.x = e.clientX; drag.y = e.clientY;
    } else if (drag.kind === 'pan') {
      panBy(e.clientX - drag.x, e.clientY - drag.y);
      drag.x = e.clientX; drag.y = e.clientY;
    } else if (drag.kind === 'hand') {
      if (drag.edgeOn || drag.prevA === null) {
        drag.acc += (e.clientY - drag.y) * 0.012 * drag.sgnZ;
        drag.y = e.clientY;
      } else {
        const a = driveAngle(e.clientX, e.clientY, drag.d);
        if (a !== null) { drag.acc += wrapPi(a - drag.prevA); drag.prevA = a; }
      }
      E.hand = { target: drag.base + drag.acc / drag.d.ratio };
    }
  });
  function endPointer(e) {
    pointers.delete(e.pointerId);
    if (drag && drag.kind === 'hand') E.hand = null;
    if (pointers.size === 0) { drag = null; canvas.classList.remove('dragging'); }
    else if (drag && drag.kind === 'pinch') { const [q] = [...pointers.values()]; drag = { kind: 'orbit', x: q.x, y: q.y }; }
  }
  canvas.addEventListener('pointerup', endPointer);
  canvas.addEventListener('pointercancel', endPointer);
  canvas.addEventListener('pointerleave', () => { if (!drag) hideTooltip(); });
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    tween = null;
    view.radius = clamp(view.radius * Math.exp(e.deltaY * 0.0012), 220, 4000);
  }, { passive: false });
  const panR = new THREE.Vector3(), panU = new THREE.Vector3();
  function panBy(dx, dy) {
    const k = view.radius * 0.0011;
    panR.setFromMatrixColumn(camera.matrix, 0);
    panU.setFromMatrixColumn(camera.matrix, 1);
    view.target.addScaledVector(panR, -dx * k).addScaledVector(panU, dy * k);
  }

  // ------------------------------------------------------------------ ficha técnica (tooltip)
  const INFO = {
    bloco: ['Bloco do motor', 'Ferro fundido, 4 cilindros em linha com entre-centros de 88 mm e 5 mancais.'],
    camisa: ['Camisa do cilindro', 'Superfície brunida onde o pistão desliza. Diâmetro 81 mm.'],
    cabecote: ['Cabeçote', 'Alumínio. Câmaras de combustão, 4 válvulas por cilindro e mancais dos dois comandos.'],
    pistao: ['Pistão', 'Alumínio com 3 anéis. Recebe a pressão dos gases e empurra a biela.'],
    biela: ['Biela', 'Aço forjado, 140 mm entre centros. Converte o vaivém do pistão em rotação.'],
    virabrequim: ['Virabrequim', 'Moentes a 180°, 5 mancais, curso de 86 mm.'],
    contrapeso: ['Contrapeso', 'Equilibra a massa do moente e da cabeça da biela.'],
    volante: ['Volante', 'Guarda energia entre as explosões e carrega a cremalheira da partida.'],
    polia: ['Polia do virabrequim', 'Amortecedor de vibração torcional; aciona os acessórios pela correia poli-V.'],
    engrenagem: ['Engrenagem do virabrequim', '19 dentes. As polias dos comandos têm o dobro, por isso giram à metade.'],
    correia: ['Correia dentada', 'Sincroniza os comandos com o virabrequim na relação 2:1.'],
    tensor: ['Tensor', 'Mantém esticado o lado frouxo da correia.'],
    bomba: ['Bomba d’água', 'Acionada pela correia dentada.'],
    comando_in: ['Comando de admissão', '8 ressaltos, 240° de duração no virabrequim, levante de 9 mm.'],
    comando_ex: ['Comando de escape', '8 ressaltos, 240° de duração no virabrequim, levante de 8,5 mm.'],
    polia_comando: ['Polia do comando', '38 dentes: uma volta a cada duas do virabrequim.'],
    valvula_in: ['Válvula de admissão', 'Ø 26 mm. Abre 10° antes do PMS e fecha 50° depois do PMI.'],
    valvula_ex: ['Válvula de escape', 'Ø 23 mm. Abre 50° antes do PMI e fecha 10° depois do PMS.'],
    mola: ['Mola de válvula', 'Fecha a válvula e mantém o tucho encostado no ressalto.'],
    tucho: ['Tucho de copo', 'Recebe o empurrão do ressalto e o passa para a haste da válvula.'],
    vela: ['Vela de ignição', 'A faísca começa a combustão antes do PMS; o avanço muda com rotação e carga.'],
    carter: ['Cárter', 'Reservatório de óleo que fecha a parte de baixo do bloco.'],
    coletor_in: ['Coletor de admissão', 'Plenum e quatro dutos até as válvulas de admissão.'],
    coletor_ex: ['Coletor de escape', 'Quatro tubos que se juntam num coletor 4-em-1.'],
    borboleta: ['Borboleta do acelerador', 'Controla a vazão de ar e, com isso, a pressão no coletor e o torque.'],
  };
  function partRows(ud) {
    const c = ud.cyl;
    const rpm = Math.abs(E.rpm);
    const rows = [];
    const cy = c !== undefined ? E.cyl[c] : null;
    switch (ud.part) {
      case 'pistao': {
        const a = E.psi(c) * DEG;
        rows.push(['Cilindro', String(c + 1)], ['Posição', fmt(S.sOf(a) * 1000, 1) + ' mm do PMS'],
          ['Velocidade', fmt(S.dsOf(a) * E.omega, 2) + ' m/s'], ['Pressão', fmt(cy.p / 1e5, 2) + ' bar'],
          ['Força dos gases', fmt(cy.F / 1000, 2) + ' kN']);
        break;
      }
      case 'biela': {
        const a = E.psi(c) * DEG;
        rows.push(['Cilindro', String(c + 1)], ['Força', fmt(Math.abs(cy.Frod) / 1000, 2) + ' kN ' + (cy.Frod >= 0 ? 'compressão' : 'tração')],
          ['Ângulo', fmt(S.rodAngle(a) / DEG, 1) + '°']);
        break;
      }
      case 'virabrequim': case 'contrapeso': case 'engrenagem': case 'polia':
        rows.push(['Rotação', fmt(rpm) + ' rpm'], ['Ângulo', fmt(E.theta, 1) + '° / 720'], ['Torque dos gases', fmt(E.tGas, 1) + ' N·m']);
        break;
      case 'volante':
        rows.push(['Rotação', fmt(rpm) + ' rpm'], ['Inércia', fmt(S.SPEC.inertia, 2) + ' kg·m²'],
          ['Energia cinética', fmt(0.5 * S.SPEC.inertia * E.omega * E.omega / 1000, 2) + ' kJ']);
        break;
      case 'correia':
        rows.push(['Velocidade', fmt(Math.abs(E.omega) * BELT_R / 1000, 2) + ' m/s'], ['Dentes', String(belt.teethN)]);
        break;
      case 'comando_in': case 'comando_ex': case 'polia_comando':
        rows.push(['Rotação', fmt(rpm / 2) + ' rpm'], ['Ângulo', fmt(wrap720(E.thetaCum) / 2, 1) + '°']);
        break;
      case 'valvula_in': case 'valvula_ex': case 'mola': case 'tucho': {
        const inn = ud.side === 'in';
        const l = inn ? S.liftIn(E.psi(c)) * S.SPEC.liftIn : S.liftEx(E.psi(c)) * S.SPEC.liftEx;
        rows.push(['Cilindro', String(c + 1)], ['Válvula', inn ? 'admissão' : 'escape'], ['Levante', fmt(l, 2) + ' mm']);
        break;
      }
      case 'vela':
        rows.push(['Cilindro', String(c + 1)], ['Avanço', fmt(E.sparkAdv, 1) + '° APMS'], ['Pico do último ciclo', fmt(cy.peakP / 1e5, 1) + ' bar']);
        break;
      case 'coletor_in': case 'borboleta':
        rows.push(['Pressão', fmt(E.pMan / 1000, 1) + ' kPa'], ['Vazão de ar', fmt(E.mdotIn * 1000, 2) + ' g/s'], ['Borboleta', fmt(E.throttle * 100) + ' %']);
        break;
      case 'coletor_ex':
        rows.push(['Contrapressão', fmt(E.pExh / 1000, 1) + ' kPa']);
        break;
      default:
        break;
    }
    return rows;
  }
  const tooltip = $('tooltip');
  let hoverAt = null, hoverTimer = 0;
  function queueHover(x, y) { hoverAt = { x, y }; }
  function hideTooltip() { tooltip.hidden = true; hover = null; canvas.classList.remove('can-turn'); }
  function renderTooltip() {
    if (!hover) return;
    const ud = hover.ud;
    const info = INFO[ud.part] || [ud.part, ''];
    const rows = partRows(ud);
    tooltip.innerHTML = `<h3>${info[0]}</h3><p>${info[1]}</p>` + (rows.length ? '<dl>' + rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('') + '</dl>' : '') +
      (ud.drive ? '<p style="margin:6px 0 0">Arraste para girar o motor à mão.</p>' : '');
    const W = innerWidth, H = innerHeight;
    const tw = tooltip.offsetWidth, th = tooltip.offsetHeight;
    let x = hover.x + 16, y = hover.y + 16;
    if (x + tw > W - 8) x = hover.x - tw - 12;
    if (y + th > H - 8) y = hover.y - th - 12;
    tooltip.style.left = Math.max(8, x) + 'px';
    tooltip.style.top = Math.max(8, y) + 'px';
  }
  function processHover(now) {
    if (!hoverAt || drag || now - hoverTimer < 60) return;
    hoverTimer = now;
    const h = pick(hoverAt.x, hoverAt.y);
    if (!h) { hideTooltip(); hoverAt = null; return; }
    hover = { ud: h.object.userData, x: hoverAt.x, y: hoverAt.y };
    canvas.classList.toggle('can-turn', !!h.object.userData.drive);
    tooltip.hidden = false;
    renderTooltip();
    hoverAt = null;
  }

  // ------------------------------------------------------------------ rótulos sobre a cena
  const overlay = $('overlay');
  function makeLabel(cls, html) { const d = document.createElement('div'); d.className = 'lbl ' + cls; d.innerHTML = html; overlay.appendChild(d); return d; }
  const phaseLabels = G.cylX.map(() => makeLabel('phase', ''));
  const torqueLabel = makeLabel('torque', '');
  const named = [
    ['Bloco', blockGroup, V3(-150, 100, -64)],
    ['Cabeçote', headGroup, V3(150, 245, -70)],
    ['Comando de admissão', cams.in, V3(100, 0, -20)],
    ['Comando de escape', cams.ex, V3(-60, 22, 10)],
    ['Correia dentada', beltGroup, V3(G.beltX, 230, -78)],
    ['Polia', noseGroup, V3(-250, 70, 0), true],
    ['Volante', flyGroup, V3(208, -142, 0), true],
    ['Virabrequim', engine, V3(-88, -28, 0)],
    ['Cárter', panGroup, V3(40, -128, 0)],
    ['Coletor de admissão', manGroup, V3(-60, 352, -205)],
    ['Coletor de escape', manGroup, V3(0, -40, 154)],
  ].map(([text, obj, local, spin]) => ({ el: makeLabel('part', text), obj, local, spin }));
  const followLabels = [
    { el: makeLabel('part', 'Pistão'), at: (v) => v.copy(cylParts[ui.follow].piston.position).add(V3(0, 31, 0)) },
    { el: makeLabel('part', 'Biela'), at: (v) => { cylParts[ui.follow].rod.localToWorld(v.set(0, 70, 0)); } },
    { el: makeLabel('part', 'Vela'), at: (v) => { headGroup.localToWorld(v.set(G.cylX[ui.follow], 326, 0)); } },
    { el: makeLabel('part', 'Mola da válvula'), at: (v) => { const vv = valves.find((q) => q.c === ui.follow && q.side === 'ex'); vv.g.localToWorld(v.set(0, 75, 12)); } },
  ];
  const proj = new THREE.Vector3();
  let W = 1, H = 1;
  function place(el, v, show, dyPx) {
    if (!show) { if (el.style.display !== 'none') el.style.display = 'none'; return; }
    proj.copy(v).project(camera);
    if (proj.z > 1 || proj.z < -1 || Math.abs(proj.x) > 1.05 || Math.abs(proj.y) > 1.05) { el.style.display = 'none'; return; }
    el.style.display = '';
    el.style.transform = `translate(${((proj.x + 1) / 2 * W).toFixed(1)}px, ${((1 - proj.y) / 2 * H + (dyPx || 0)).toFixed(1)}px) translate(-50%, ${el.classList.contains('part') ? 'calc(-100% - 10px)' : '-50%'})`;
  }
  const lblV = new THREE.Vector3();
  function updateLabels() {
    G.cylX.forEach((x, c) => {
      const ok = ui.opt.phase && cylParts[c].piston.visible;
      lblV.set(x, 404 + headGroup.position.y + camGroup.position.y, 0);
      place(phaseLabels[c], lblV, ok, c % 2 ? 24 : 0);
      if (ok) {
        const cy = E.cyl[c];
        let ph = S.phaseOf(cy.psi);
        if (ph === 1 && cy.burnStart !== null && cy.xb > 0.002) ph = 2;
        const html = `<span style="color:${PHASE_COL[ph]}">${c + 1} · ${(W > 860 ? PHASE_UP : PHASE_SHORT)[ph]}</span>` + (c === ui.follow && W > 860 ? `<small>${Math.floor(cy.psi)}°</small>` : '');
        if (phaseLabels[c]._h !== html) { phaseLabels[c].innerHTML = html; phaseLabels[c]._h = html; }
      }
    });
    named.forEach((n) => { n.obj.localToWorld(lblV.copy(n.local)); if (n.spin) { lblV.y = n.local.y; lblV.z = n.local.z; } place(n.el, lblV, ui.opt.names && shown(n.obj)); });
    followLabels.forEach((f) => { f.at(lblV); place(f.el, lblV, ui.opt.names); });
    const tq = E.tGas + E.tIn;
    lblV.set(232, 175, 0);
    place(torqueLabel, lblV, torqueArrow.g.visible);
    const txt = `T = ${fmt(tq, 0)} N·m`;
    if (torqueLabel._t !== txt) { torqueLabel.textContent = txt; torqueLabel._t = txt; }
  }

  // ------------------------------------------------------------------ gráficos
  function setupCanvas(id) {
    const c = $(id);
    const ctx = c.getContext('2d');
    const o = { c, ctx, w: 1, h: 1, draw: null };
    const fit = () => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      o.w = Math.max(1, c.clientWidth); o.h = Math.max(1, c.clientHeight);
      c.width = Math.round(o.w * dpr); c.height = Math.round(o.h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      if (o.draw) o.draw();
    };
    fit();
    if (window.ResizeObserver) new ResizeObserver(fit).observe(c);
    return o;
  }
  const charts = { pv: setupCanvas('cPV'), dist: setupCanvas('cDist'), tq: setupCanvas('cTq'), tel: setupCanvas('cTel') };
  const MONO = '10px "IBM Plex Mono", ui-monospace, monospace';

  function axisText(ctx, s, x, y, align, color) {
    ctx.fillStyle = color || COL.dim; ctx.font = MONO; ctx.textAlign = align || 'left'; ctx.textBaseline = 'middle';
    ctx.fillText(s, x, y);
  }

  function drawPV() {
    const { ctx, w, h } = charts.pv;
    ctx.clearRect(0, 0, w, h);
    const L = 30, R = 6, T = 6, B = 14;
    const V0 = Math.log(40), V1 = Math.log(520), P0 = Math.log(0.2), P1 = Math.log(100);
    const X = (v) => L + (Math.log(v) - V0) / (V1 - V0) * (w - L - R);
    const Y = (p) => T + (1 - (Math.log(Math.max(p, 0.2)) - P0) / (P1 - P0)) * (h - T - B);
    ctx.strokeStyle = COL.line; ctx.lineWidth = 1;
    [0.2, 1, 5, 20, 100].forEach((p) => {
      const y = Math.round(Y(p)) + 0.5;
      ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke();
      axisText(ctx, fmt(p, p < 1 ? 1 : 0), L - 4, y, 'right');
    });
    [50, 100, 200, 400].forEach((v) => {
      const x = Math.round(X(v)) + 0.5;
      ctx.beginPath(); ctx.moveTo(x, T); ctx.lineTo(x, h - B); ctx.stroke();
      axisText(ctx, v + ' cm³', x, h - 5, 'center');
    });
    axisText(ctx, 'bar', 2, T + 4, 'left');
    const pb = E.bins.p[ui.follow];
    ctx.lineWidth = 1.6;
    for (let ph = 0; ph < 4; ph++) {
      ctx.strokeStyle = PHASE_COL[ph];
      ctx.beginPath();
      for (let k = ph * 180; k <= ph * 180 + 180; k++) {
        const kk = k % 720;
        const x = X(S.volume(kk) * 1e6), y = Y(pb[kk] / 1e5);
        if (k === ph * 180) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }
    const c = E.cyl[ui.follow];
    ctx.fillStyle = '#fff';
    ctx.beginPath(); ctx.arc(X(S.volume(c.psi) * 1e6), Y(c.p / 1e5), 3.2, 0, TAU); ctx.fill();
  }

  function arc(ctx, cx, cy, r, a0, a1, color, width) {
    // ângulos do ciclo em graus; 0 no alto, sentido horário
    ctx.strokeStyle = color; ctx.lineWidth = width;
    ctx.beginPath(); ctx.arc(cx, cy, r, (a0 - 90) * DEG, (a1 - 90) * DEG); ctx.stroke();
  }
  function drawDist() {
    const { ctx, w, h } = charts.dist;
    ctx.clearRect(0, 0, w, h);
    const c = E.cyl[ui.follow];
    const psi = c.psi;
    const rev = psi < 360 ? 0 : 1;
    const R = Math.min(h / 2 - 8, w / 2 - 60);
    const cx = w / 2, cy = h / 2 + 2;
    const ro = R, ri = R - 9;
    ctx.globalAlpha = 1;
    [ro, ri].forEach((r, k) => {
      ctx.globalAlpha = k === rev ? 1 : 0.35;
      arc(ctx, cx, cy, r, 0, 360, '#1d2227', 6);
      // admissão 710..230, escape 490..730
      const off = k * 360;
      const segs = [[710, 950, COL.adm], [490, 730, COL.comb]];
      segs.forEach(([a, b, col]) => {
        for (const base of [0, 720]) {
          const s0 = Math.max(a - base, off), s1 = Math.min(b - base, off + 360);
          if (s1 > s0) arc(ctx, cx, cy, r, s0 - off, s1 - off, col, 6);
        }
      });
    });
    ctx.globalAlpha = 1;
    const spark = 360 - E.sparkAdv;
    const sr = spark < 360 ? ro : ri;
    const sa = (spark % 360 - 90) * DEG;
    ctx.fillStyle = '#ffe08a';
    ctx.beginPath(); ctx.arc(cx + Math.cos(sa) * sr, cy + Math.sin(sa) * sr, 3, 0, TAU); ctx.fill();
    const na = ((psi % 360) - 90) * DEG;
    const nr = rev === 0 ? ro : ri;
    ctx.strokeStyle = '#f4f1ea'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + Math.cos(na) * (nr + 5), cy + Math.sin(na) * (nr + 5)); ctx.stroke();
    ctx.fillStyle = '#f4f1ea'; ctx.beginPath(); ctx.arc(cx, cy, 3, 0, TAU); ctx.fill();
    axisText(ctx, 'PMS', cx, cy - ro - 7 < 6 ? 6 : cy - ro - 7, 'center', COL.muted);
    axisText(ctx, 'PMI', cx, Math.min(h - 5, cy + ro + 7), 'center', COL.muted);
    axisText(ctx, rev === 0 ? '1ª volta' : '2ª volta', w - 4, 9, 'right', COL.muted);
    axisText(ctx, 'admissão', 4, h - 17, 'left', COL.adm);
    axisText(ctx, 'escape', 4, h - 6, 'left', COL.comb);
    axisText(ctx, `faísca ${fmt(E.sparkAdv, 0)}° APMS`, w - 4, h - 6, 'right', '#ffe08a');
    const lI = S.liftIn(psi) * S.SPEC.liftIn, lE = S.liftEx(psi) * S.SPEC.liftEx;
    axisText(ctx, `adm ${fmt(lI, 1)} mm`, 4, 9, 'left', lI > 0 ? COL.adm : COL.dim);
    axisText(ctx, `esc ${fmt(lE, 1)} mm`, 4, 21, 'left', lE > 0 ? COL.comb : COL.dim);
  }

  function niceStep(span) {
    const raw = span / 4;
    const p = Math.pow(10, Math.floor(Math.log10(raw)));
    const n = raw / p;
    return (n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10) * p;
  }
  let tqRange = { lo: -50, hi: 50 };
  function drawTq() {
    const { ctx, w, h } = charts.tq;
    ctx.clearRect(0, 0, w, h);
    const b = E.bins;
    let lo = 0, hi = 0;
    for (let k = 0; k < 720; k++) { lo = Math.min(lo, b.tGas[k], b.tTot[k]); hi = Math.max(hi, b.tGas[k], b.tTot[k]); }
    tqRange.lo += (Math.min(lo, -20) - tqRange.lo) * 0.15;
    tqRange.hi += (Math.max(hi, 20) - tqRange.hi) * 0.15;
    const step = niceStep(tqRange.hi - tqRange.lo);
    const ylo = Math.floor(tqRange.lo / step) * step, yhi = Math.ceil(tqRange.hi / step) * step;
    const L = 34, R = 6, T = 6, B = 14;
    const X = (a) => L + a / 720 * (w - L - R);
    const Y = (v) => T + (yhi - v) / (yhi - ylo) * (h - T - B);
    ctx.lineWidth = 1;
    for (let v = ylo; v <= yhi + 1e-6; v += step) {
      const y = Math.round(Y(v)) + 0.5;
      ctx.strokeStyle = v === 0 ? '#3a414a' : COL.line;
      ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke();
      axisText(ctx, fmt(v), L - 4, y, 'right');
    }
    [0, 180, 360, 540, 720].forEach((a) => axisText(ctx, a + '°', X(a), h - 5, a === 0 ? 'left' : a === 720 ? 'right' : 'center'));
    const line = (arr, col, lw) => {
      ctx.strokeStyle = col; ctx.lineWidth = lw; ctx.beginPath();
      for (let k = 0; k < 720; k++) { const x = X(k), y = Y(arr[k]); if (k) ctx.lineTo(x, y); else ctx.moveTo(x, y); }
      ctx.stroke();
    };
    line(b.tTot, 'rgba(232,226,214,0.75)', 1.2);
    line(b.tGas, COL.comb, 1.6);
    const m = Math.abs(E.rpm) > 50 ? E.meanTorque() : 0;
    ctx.setLineDash([4, 3]); ctx.strokeStyle = COL.ok; ctx.lineWidth = 1;
    const ym = Math.round(Y(m)) + 0.5;
    ctx.beginPath(); ctx.moveTo(L, ym); ctx.lineTo(w - R, ym); ctx.stroke();
    ctx.setLineDash([]);
    axisText(ctx, `médio ${fmt(m, 1)} N·m`, w - R - 2, ym - 7, 'right', COL.ok);
    const xc = Math.round(X(E.theta)) + 0.5;
    ctx.strokeStyle = 'rgba(244,241,234,0.5)';
    ctx.beginPath(); ctx.moveTo(xc, T); ctx.lineTo(xc, h - B); ctx.stroke();
  }

  const TEL_N = 600;
  const tel = { rpm: new Float32Array(TEL_N), tq: new Float32Array(TEL_N), p: new Float32Array(TEL_N), head: 0, n: 0 };
  function sampleTel() {
    const k = tel.head;
    tel.rpm[k] = Math.abs(E.rpm);
    tel.tq[k] = Math.abs(E.rpm) > 50 ? E.meanTorque() : 0;
    tel.p[k] = E.cyl[ui.follow].p / 1e5;
    tel.head = (k + 1) % TEL_N;
    tel.n = Math.min(TEL_N, tel.n + 1);
  }
  function drawTel() {
    const { ctx, w, h } = charts.tel;
    ctx.clearRect(0, 0, w, h);
    const L = 30, R = 28, T = 6, B = 8;
    const ih = h - T - B;
    ctx.strokeStyle = COL.line; ctx.lineWidth = 1;
    for (let k = 0; k <= 4; k++) { const y = Math.round(T + ih * k / 4) + 0.5; ctx.beginPath(); ctx.moveTo(L, y); ctx.lineTo(w - R, y); ctx.stroke(); }
    axisText(ctx, '7000', L - 4, T + 2, 'right', COL.accent);
    axisText(ctx, '0', L - 4, h - B, 'right', COL.accent);
    axisText(ctx, '200', w - R + 4, T + 2, 'left', COL.ok);
    axisText(ctx, '−50', w - R + 4, h - B, 'left', COL.ok);
    axisText(ctx, '80 bar', L + 4, T + 8, 'left', COL.comp);
    const series = (arr, lo, hi, col) => {
      ctx.strokeStyle = col; ctx.lineWidth = 1.4; ctx.beginPath();
      for (let k = 0; k < tel.n; k++) {
        const idx = (tel.head - tel.n + k + TEL_N) % TEL_N;
        const x = L + (k + TEL_N - tel.n) / (TEL_N - 1) * (w - L - R);
        const y = T + (1 - clamp((arr[idx] - lo) / (hi - lo), 0, 1)) * ih;
        if (k) ctx.lineTo(x, y); else ctx.moveTo(x, y);
      }
      ctx.stroke();
    };
    series(tel.p, 0, 80, COL.comp);
    series(tel.tq, -50, 200, COL.ok);
    series(tel.rpm, 0, 7000, COL.accent);
  }

  charts.pv.draw = drawPV; charts.dist.draw = drawDist; charts.tq.draw = drawTq; charts.tel.draw = drawTel;

  // ------------------------------------------------------------------ painel de telemetria
  const cylCards = $('cylCards');
  cylCards.innerHTML = [0, 1, 2, 3].map((c) => `<button class="cyl" type="button" data-cyl="${c}" aria-pressed="false"><b>CIL ${c + 1}</b><span class="num pv" data-p>0 bar</span><span class="ph" data-ph></span></button>`).join('');
  const cardEls = [...cylCards.children].map((b) => ({ b, p: b.querySelector('[data-p]'), ph: b.querySelector('[data-ph]') }));
  const hudEls = {
    rpm: $('vRpm'), tq: $('vTq'), kw: $('vKw'), thr: $('vThr'), load: $('vLoad'), ti: $('vTi'), man: $('vMan'),
    adv: $('vAdv'), imep: $('vImep'), ang: $('vAng'), cam: $('vCam'), status: $('vStatus'),
  };
  const setText = (el, s) => { if (el.textContent !== s) el.textContent = s; };
  function updateHud() {
    const rpm = Math.abs(E.rpm);
    const moving = rpm > 50;
    const tq = moving ? E.meanTorque() : 0;
    setText(hudEls.rpm, fmt(rpm));
    setText(hudEls.tq, fmt(tq));
    setText(hudEls.kw, fmt(Math.max(0, tq) * E.omega / 1000, 1));
    setText(hudEls.thr, fmt(E.throttle * 100) + ' %');
    setText(hudEls.load, fmt(E.load) + ' N·m');
    setText(hudEls.ti, fmt(E.tGas + E.tIn) + ' N·m');
    setText(hudEls.man, fmt(E.pMan / 1000) + ' kPa');
    setText(hudEls.adv, fmt(E.sparkAdv, 1) + '° APMS');
    setText(hudEls.imep, fmt(moving ? E.meanImep() / 1e5 : 0, 1) + ' bar');
    setText(hudEls.ang, fmt(E.theta) + '° / 720');
    setText(hudEls.cam, fmt(rpm / 2) + ' rpm');
    const st = E.status;
    setText(hudEls.status, 'Motor ' + st);
    hudEls.status.className = 'status' + (st === 'em funcionamento' ? '' : st === 'desligado' || st === 'desacelerando' ? ' off' : ' warn');
    cardEls.forEach((e, c) => {
      const cy = E.cyl[c];
      let ph = S.phaseOf(cy.psi);
      if (ph === 1 && cy.burnStart !== null && cy.xb > 0.002) ph = 2;
      setText(e.p, fmt(cy.p / 1e5, 1) + ' bar');
      setText(e.ph, S.PHASES[ph]);
      e.ph.className = 'ph p' + ph;
      e.b.setAttribute('aria-pressed', String(c === ui.follow));
    });
    setText($('tbClock'), `t = ${fmt(E.t, 2)} s · ${fmtX(ui.speed)}`);
    setText($('speedNote'), `solicitada ${fmtX(ui.speed)} · efetiva ${fmtX(ui.paused ? 0 : ui.effective)}`);
    const c = E.cyl[ui.follow];
    let ph = S.phaseOf(c.psi);
    if (ph === 1 && c.burnStart !== null && c.xb > 0.002) ph = 2;
    setText($('distInfo'), `${Math.floor(c.psi)}° · ${S.PHASES[ph]}`);
    if (hover) renderTooltip();
  }

  // ------------------------------------------------------------------ som
  const audio = { ctx: null, master: null, noise: null };
  function audioInit() {
    if (audio.ctx) { audio.ctx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    const ctx = new AC();
    const comp = ctx.createDynamicsCompressor();
    const master = ctx.createGain();
    master.gain.value = 0.55;
    master.connect(comp).connect(ctx.destination);
    const buf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate);
    const d = buf.getChannelData(0);
    for (let k = 0; k < d.length; k++) d[k] = Math.random() * 2 - 1;
    Object.assign(audio, { ctx, master, noise: buf });
  }
  function playFire(when, strength, rpm, interval) {
    const ctx = audio.ctx;
    const decay = clamp(interval * 0.9, 0.012, 0.14);
    const src = ctx.createBufferSource();
    src.buffer = audio.noise;
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = 380 + rpm * 0.12;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, when);
    g.gain.linearRampToValueAtTime(0.5 * strength, when + 0.002);
    g.gain.exponentialRampToValueAtTime(0.0001, when + decay);
    src.connect(lp).connect(g).connect(audio.master);
    src.start(when, Math.random() * 0.8, decay + 0.03);
    const o = ctx.createOscillator();
    o.type = 'triangle';
    o.frequency.setValueAtTime(95 + rpm * 0.01, when);
    o.frequency.exponentialRampToValueAtTime(40, when + decay);
    const og = ctx.createGain();
    og.gain.setValueAtTime(0.0001, when);
    og.gain.linearRampToValueAtTime(0.7 * strength, when + 0.003);
    og.gain.exponentialRampToValueAtTime(0.0001, when + decay);
    o.connect(og).connect(audio.master);
    o.start(when);
    o.stop(when + decay + 0.03);
  }
  function flushSound(frameT0, speedEff) {
    const evs = E.events;
    if (ui.opt.sound && audio.ctx && evs.length) {
      const rpm = Math.max(Math.abs(E.rpm), 60);
      const interval = (60 / rpm / 2) / Math.max(speedEff, 1e-4);
      const base = audio.ctx.currentTime + 0.05;
      const take = evs.length > 24 ? evs.slice(-24) : evs;
      for (const ev of take) {
        const off = clamp((ev.t - frameT0) / Math.max(speedEff, 1e-4), 0, 0.1);
        playFire(base + off, clamp(ev.Q / 1400, 0.15, 1), rpm, interval);
      }
    }
    evs.length = 0;
  }

  // ------------------------------------------------------------------ controles
  const speedEl = $('speed');
  const toSlider = (s) => Math.round((Math.log10(s) + 3) / 4.3 * 1000);
  const fromSlider = (v) => Math.pow(10, -3 + 4.3 * v / 1000);
  function setSpeed(s) {
    ui.speed = clamp(s, 0.001, 20);
    ui.effective = ui.speed;
    speedEl.value = toSlider(ui.speed);
    setText($('speedOut'), fmtX(ui.speed));
    document.querySelectorAll('#speedPresets .btn').forEach((b) => b.setAttribute('aria-pressed', String(Math.abs(+b.dataset.speed - ui.speed) / ui.speed < 0.01)));
  }
  speedEl.addEventListener('input', () => setSpeed(fromSlider(+speedEl.value)));
  document.querySelectorAll('#speedPresets .btn').forEach((b) => b.addEventListener('click', () => setSpeed(+b.dataset.speed)));
  $('cycle10').addEventListener('click', () => setSpeed(12 / Math.max(Math.abs(E.rpm), 100)));

  function setPaused(p) {
    ui.paused = p;
    const lbl = p ? 'Continuar' : 'Pausar';
    $('tbPause').querySelector('span').textContent = lbl;
    $('tbPause').querySelector('svg').innerHTML = p ? '<path d="M2 1l9 5-9 5z"/>' : '<path d="M2 1h3v10H2zM7 1h3v10H7z"/>';
    $('pPause').textContent = lbl;
    $('pPause').setAttribute('aria-pressed', String(p));
  }
  $('tbPause').addEventListener('click', () => setPaused(!ui.paused));
  $('pPause').addEventListener('click', () => setPaused(!ui.paused));
  function stepDeg() { setPaused(true); E.stepDegrees(1); E.events.length = 0; }
  function stepFrame() { setPaused(true); E.advance(ui.speed / 60, 6000); E.events.length = 0; }
  $('tbStep').addEventListener('click', stepDeg);
  $('pFrame').addEventListener('click', stepFrame);

  $('btnStart').addEventListener('click', () => { E.start(); if (ui.paused) setPaused(false); });
  $('btnStop').addEventListener('click', () => E.stop());
  const thrEl = $('thr'), loadEl = $('load');
  function setThrottle(v) { E.throttle = clamp(v, 0, 1); thrEl.value = Math.round(E.throttle * 100); setText($('thrOut'), fmt(E.throttle * 100) + ' %'); }
  thrEl.addEventListener('input', () => setThrottle(+thrEl.value / 100));
  loadEl.addEventListener('input', () => { E.load = +loadEl.value; setText($('loadOut'), fmt(E.load) + ' N·m'); });

  function setFollow(c) {
    ui.follow = c;
    $('pvTitle').textContent = `P × V · cil. ${c + 1}`;
    $('distTitle').textContent = `Distribuição · cil. ${c + 1}`;
    document.querySelectorAll('#followSeg .btn').forEach((b) => b.setAttribute('aria-pressed', String(+b.dataset.cyl === c)));
    resetTrails();
    if (ui.section === 'side') { applyMode(); const [p, t] = CAMS.lateral(); flyTo(p, t); }
  }
  document.querySelectorAll('#followSeg .btn').forEach((b) => b.addEventListener('click', () => setFollow(+b.dataset.cyl)));
  cylCards.addEventListener('click', (e) => { const b = e.target.closest('.cyl'); if (b) setFollow(+b.dataset.cyl); });

  function setMode(m) { ui.mode = m; applyMode(); }
  document.querySelectorAll('#modeSeg .btn').forEach((b) => b.addEventListener('click', () => setMode(b.dataset.mode)));
  $('explode').addEventListener('input', (e) => { ui.explode = +e.target.value / 100; setText($('explodeOut'), e.target.value + ' %'); applyExplode(); });
  const optMap = { oGases: 'gases', oArrows: 'arrows', oTrails: 'trails', oPhase: 'phase', oNames: 'names', oRodForce: 'rodForce', oFlow: 'flow', oPan: 'pan', oSound: 'sound' };
  Object.entries(optMap).forEach(([id, k]) => {
    $(id).addEventListener('change', (e) => {
      ui.opt[k] = e.target.checked;
      if (k === 'pan') panGroup.visible = ui.opt.pan;
      if (k === 'sound' && ui.opt.sound) audioInit();
      if (k === 'trails') resetTrails();
    });
  });
  document.querySelectorAll('[data-cam]').forEach((b) => b.addEventListener('click', () => setCamera(b.dataset.cam)));

  const panel = $('panel');
  function setPanel(open) {
    panel.classList.toggle('collapsed', !open);
    $('panelOpen').hidden = open;
    document.body.classList.toggle('panel-hidden', !open);
    resize();
  }
  $('panelClose').addEventListener('click', () => setPanel(false));
  $('panelOpen').addEventListener('click', () => setPanel(true));

  function openModal(id) {
    const m = $(id);
    m.hidden = false;
    const btn = m.querySelector('[data-close]');
    if (btn) btn.focus();
  }
  document.querySelectorAll('.modal').forEach((m) => {
    m.addEventListener('click', (e) => { if (e.target === m || e.target.closest('[data-close]')) m.hidden = true; });
  });
  $('tbHelp').addEventListener('click', () => openModal('helpModal'));
  $('tbValidate').addEventListener('click', () => {
    const res = E.validate();
    $('validTable').innerHTML = res.map((r) => `<tr><td class="${r.ok ? 'ok' : 'bad'}">${r.ok ? '✓' : '✗'}</td><td>${r.name}<small>${r.detail.replace(/(\d)\.(\d)/g, '$1,$2')}</small></td></tr>`).join('');
    const okN = res.filter((r) => r.ok).length;
    $('validSub').textContent = `${okN} de ${res.length} verificações aprovadas · t = ${fmt(E.t, 2)} s, ${fmt(Math.abs(E.rpm))} rpm`;
    openModal('validModal');
  });

  window.addEventListener('keydown', (e) => {
    if (e.target.closest && e.target.closest('input, textarea')) { if (e.key !== ' ') return; }
    const open = document.querySelector('.modal:not([hidden])');
    if (e.key === 'Escape') { if (open) open.hidden = true; return; }
    if (open || e.metaKey || e.ctrlKey || e.altKey) return;
    const k = e.key.toLowerCase();
    if (e.key === ' ') { e.preventDefault(); setPaused(!ui.paused); }
    else if (e.key === '.') stepDeg();
    else if (k === 'f') stepFrame();
    else if ('1234'.includes(e.key)) setFollow(+e.key - 1);
    else if (k === 'n') setMode('normal');
    else if (k === 'c') setMode('corte');
    else if (k === 'x') setMode('raiox');
    else if (k === 's') { E.start(); if (ui.paused) setPaused(false); }
    else if (k === 'd') E.stop();
    else if (e.key === 'ArrowUp') { e.preventDefault(); setThrottle(E.throttle + 0.05); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); setThrottle(E.throttle - 0.05); }
    else if (e.key === '[') setSpeed(ui.speed / 2);
    else if (e.key === ']') setSpeed(ui.speed * 2);
    else if (e.key === '?') openModal('helpModal');
  });

  // ------------------------------------------------------------------ redimensionamento
  function resize() {
    W = window.innerWidth; H = window.innerHeight;
    renderer.setSize(W, H, false);
    camera.aspect = W / H;
    fitK = Math.max(1, 1.05 / camera.aspect);
    const wide = W > 860;
    const panelOpen = !panel.classList.contains('collapsed');
    // centraliza o motor no espaço livre entre a telemetria (esquerda) e os controles (direita)
    const dx = wide ? ((panelOpen ? 314 : 0) - 306) / 2 : 0;
    const dy = wide ? 62 : -36;
    camera.setViewOffset(W, H, dx, dy, W, H);
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', resize);

  // ------------------------------------------------------------------ laço principal
  let last = performance.now();
  let hudT = 0, chartFlip = 0;
  function frame(now) {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    const t0 = E.t;
    let speedEff = ui.speed;
    if (E.hand) {
      E.advance(dt, 8000);
      speedEff = 1;
    } else if (!ui.paused) {
      const req = dt * ui.speed;
      const done = E.advance(req, 6000);
      ui.effective += ((req > 0 ? ui.speed * done / req : ui.speed) - ui.effective) * 0.2;
      speedEff = ui.effective;
    }
    flushSound(t0, speedEff);
    updatePose();
    stepTween(now);
    applyView();
    updateLabels();
    processHover(now);
    if (!ui.paused || E.hand) sampleTel();
    chartFlip ^= 1;
    if (chartFlip) { drawPV(); drawDist(); } else { drawTq(); drawTel(); }
    if (now - hudT > 100) { hudT = now; updateHud(); }
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }

  // estado inicial
  setSpeed(ui.speed);
  setPaused(ui.paused);
  setThrottle(0);
  setFollow(0);
  applyMode();
  applyExplode();
  if (window.innerWidth <= 860) setPanel(false); else resize();
  setCamera('geral', true);
  updatePose();
  drawPV(); drawDist(); drawTq(); drawTel(); updateHud();
  requestAnimationFrame(frame);

  // ganchos para inspeção no console
  window.motor = { engine: E, ui, camera, setCamera, setMode, setFollow };
})();
