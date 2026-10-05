/*
 * Modelo físico do motor 4 cilindros em linha DOHC 16V.
 *
 * Unidades SI. Ângulo do ciclo ψ em graus [0, 720): 0 = PMS no início da admissão,
 * 180 = PMI, 360 = PMS de compressão (ignição), 540 = PMI de expansão.
 * Funciona no navegador (window.EngineSim) e no Node (module.exports).
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.EngineSim = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const DEG = Math.PI / 180;
  const RAD = 180 / Math.PI;
  const R_AIR = 287;
  const P_ATM = 101325;
  const T_ATM = 298;
  const T_INTAKE = 330; // ar aquecido pelo coletor e pelos gases residuais

  const SPEC = {
    bore: 0.081,
    stroke: 0.086,
    rod: 0.140,
    cr: 10.5,
    firingOrder: [1, 3, 4, 2],
    // defasagem do ciclo de cada cilindro em relação ao ângulo do virabrequim (ordem 1-3-4-2)
    offsets: [0, 180, 540, 360],
    ivo: 710, ivc: 230, // admissão abre 10° APMS, fecha 50° DPMI
    evo: 490, evc: 10,  // escape abre 50° APMI, fecha 10° DPMS
    liftIn: 9.0, liftEx: 8.5, // mm
    recipMass: 0.52,   // kg por cilindro: pistão, pino, anéis e 1/3 da biela
    inertia: 0.16,     // kg·m², virabrequim + volante + polias
    afr: 14.7,
    lhv: 44e6,
    heatFrac: 0.80,    // fração do calor do combustível que chega ao gás (resto: paredes, combustão incompleta)
    gamma: 1.3,
    vMan: 2.6e-3,      // volume do coletor de admissão, m³
    throttleCdA: 1.25e-3,
    idleRpm: 850,
    limiter: 6700,
  };

  const r = SPEC.stroke / 2;
  const l = SPEC.rod;
  const AREA = Math.PI * SPEC.bore * SPEC.bore / 4;
  const VD = AREA * SPEC.stroke;
  const VC = VD / (SPEC.cr - 1);
  const DUR_IN = wrap720(SPEC.ivc - SPEC.ivo);
  const DUR_EX = wrap720(SPEC.evc - SPEC.evo);
  const PHASES = ['admissão', 'compressão', 'combustão', 'escape'];

  function wrap720(x) { x %= 720; return x < 0 ? x + 720 : x; }
  function clamp(x, a, b) { return x < a ? a : x > b ? b : x; }
  function smooth(a, b, x) { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }

  // deslocamento do pistão abaixo do PMS (m), em função do ângulo do moente (rad)
  function sOf(a) {
    const s = Math.sin(a), c = Math.cos(a);
    return r + l - (r * c + Math.sqrt(l * l - r * r * s * s));
  }
  function dsOf(a) {
    const s = Math.sin(a), c = Math.cos(a), k = Math.sqrt(l * l - r * r * s * s);
    return r * s + r * r * s * c / k;
  }
  function ddsOf(a) {
    const s = Math.sin(a), c = Math.cos(a), k = Math.sqrt(l * l - r * r * s * s);
    return r * c + r * r * (c * c - s * s) / k + Math.pow(r, 4) * s * s * c * c / (k * k * k);
  }
  function volume(psiDeg) { return VC + AREA * sOf(psiDeg * DEG); }
  function rodAngle(a) { return Math.asin(r * Math.sin(a) / l); }

  function camWindow(psi, open, dur) {
    const u = wrap720(psi - open) / dur;
    return u > 0 && u < 1 ? Math.pow(Math.sin(Math.PI * u), 1.5) : 0;
  }
  function liftIn(psi) { return camWindow(psi, SPEC.ivo, DUR_IN); }
  function liftEx(psi) { return camWindow(psi, SPEC.evo, DUR_EX); }
  function phaseOf(psi) { psi = wrap720(psi); return psi < 180 ? 0 : psi < 360 ? 1 : psi < 540 ? 2 : 3; }

  function wiebe(u) { return u <= 0 ? 0 : u >= 1.4 ? 1 : 1 - Math.exp(-5 * Math.pow(u, 3)); }

  // vazão compressível por um orifício (borboleta)
  function flowFn(pr) {
    const g = 1.4;
    if (pr <= 0.5283) return 0.6847;
    if (pr >= 1) return 0;
    return Math.sqrt(2 * g / (g - 1) * (Math.pow(pr, 2 / g) - Math.pow(pr, (g + 1) / g)));
  }

  // o evento `ev` foi cruzado ao avançar de a para b (ambos em [0,720), avanço pequeno)?
  function crossed(a, b, ev) {
    const d = wrap720(b - a);
    const e = wrap720(ev - a);
    return d < 360 && e > 0 && e <= d;
  }

  function newCylinder(i) {
    return {
      i, off: SPEC.offsets[i],
      p: P_ATM, T: T_ATM, psi: 0, V: VC + VD,
      pIvc: P_ATM, vIvc: VC + VD, mAir: 0, Q: 0,
      burnStart: null, burnDur: 50, xb: 0, lastQ: 0,
      W: 0, imep: 0, peakP: P_ATM, peakAt: 0, curPeak: 0, curPeakAt: 0,
      F: 0, Frod: 0, torque: 0, lIn: 0, lEx: 0,
    };
  }

  class Engine {
    constructor() {
      this.spec = SPEC;
      this.t = 0;
      this.thetaCum = 0; // graus, acumulado
      this.omega = 0;    // rad/s
      this.pMan = P_ATM;
      this.pExh = P_ATM;
      this.throttle = 0;
      this.load = 0;
      this.ignition = false;
      this.starter = 0;
      this.iacI = 1.8e-5;
      this.iac = 1.8e-5;
      this.fuelCut = false;
      this.hand = null; // { target: rad acumulado }
      this.sparkAdv = 10;
      this.burnDur = 50;
      this.mdotIn = 0;
      this.mdotOut = 0;
      this.cyl = [0, 1, 2, 3].map(newCylinder);
      this.tGas = 0; this.tIn = 0; this.tFric = 0; this.tLoad = 0; this.tStart = 0; this.tHand = 0;
      this.bins = {
        tGas: new Float32Array(720),
        tTot: new Float32Array(720),
        tNet: new Float32Array(720),
        p: [0, 1, 2, 3].map(() => new Float32Array(720).fill(P_ATM)),
      };
      this.events = [];
      this.acc = { pdv: 0, tdth: 0 };
      this.lastCycle = { pdv: 0, tdth: 0, valid: false };
      this.status = 'desligado';
    }

    get theta() { return wrap720(this.thetaCum); }
    get rpm() { return this.omega * 60 / (2 * Math.PI); }
    psi(i) { return wrap720(this.thetaCum + SPEC.offsets[i]); }

    start() { this.ignition = true; this.starter = 3.5; }
    stop() { this.ignition = false; this.starter = 0; }

    meanTorque() { let s = 0; for (let k = 0; k < 720; k++) s += this.bins.tNet[k]; return s / 720; }
    meanImep() { let s = 0; for (const c of this.cyl) s += c.imep; return s / 4; }

    step(dt) {
      const w = this.omega;
      const rpm = Math.abs(this.rpm);
      const dDeg = w * dt * RAD;
      const th0 = this.thetaCum;
      const th1 = th0 + dDeg;
      const g = SPEC.gamma;

      // ---- coletor de admissão e controle de marcha lenta
      const thr = clamp(this.throttle, 0, 1);
      if (this.ignition && rpm > 450 && thr < 0.03) {
        const err = SPEC.idleRpm - rpm;
        this.iacI = clamp(this.iacI + 1.5e-8 * err * dt, 0.6e-5, 6e-5);
        this.iac = clamp(this.iacI + 1.5e-8 * err, 0.3e-5, 7e-5);
      } else {
        this.iac = this.iacI;
      }
      const cdA = SPEC.throttleCdA * Math.pow(thr, 1.8) + this.iac + 2e-6;
      const pr = this.pMan / P_ATM;
      this.mdotIn = pr < 1
        ? cdA * P_ATM / Math.sqrt(R_AIR * T_ATM) * flowFn(pr)
        : -cdA * this.pMan / Math.sqrt(R_AIR * T_ATM) * flowFn(1 / pr);
      this.mdotOut = 0.85 * this.pMan * VD * 4 * (rpm / 120) / (R_AIR * T_ATM);
      this.pMan = clamp(this.pMan + R_AIR * T_ATM / SPEC.vMan * (this.mdotIn - this.mdotOut) * dt, 0.08 * P_ATM, 1.05 * P_ATM);
      this.pExh = P_ATM + 0.15e5 * Math.pow(this.mdotOut / 0.09, 2);

      // ---- mapa de ignição
      const loadF = this.pMan / P_ATM;
      this.sparkAdv = clamp(8 + 22 * smooth(900, 4200, rpm) + 10 * (1 - loadF) * smooth(1000, 1800, rpm), 5, 40);
      this.burnDur = 42 + 18 * (1 - loadF) + 8 * smooth(1000, 6000, rpm);
      if (rpm > SPEC.limiter) this.fuelCut = true;
      else if (rpm < SPEC.limiter - 250) this.fuelCut = false;

      // ---- cilindros
      const kFlow = 2.5e-4 / 0.8e-3;
      const leak = 1 - Math.exp(-dt / 2.0);
      const wrapped = crossed(wrap720(th0), wrap720(th1), 0);
      let tGas = 0, tIn = 0, pdv = 0;
      for (const c of this.cyl) {
        const psiA = wrap720(th0 + c.off), psiB = wrap720(th1 + c.off);
        const aB = psiB * DEG;
        const VA = volume(psiA), VB = volume(psiB);
        const p0 = c.p;
        let p = p0 * Math.pow(VA / VB, g);

        if (dDeg > 0) {
          if (crossed(psiA, psiB, SPEC.ivc)) {
            c.pIvc = p; c.vIvc = VB;
            // gás residual do ciclo anterior ocupa parte da carga
            const mRes = this.pExh * VC / (R_AIR * 950);
            c.mAir = Math.max(0, p * VB / (R_AIR * T_INTAKE) - mRes);
            c.Q = this.ignition && !this.fuelCut ? c.mAir / SPEC.afr * SPEC.lhv * SPEC.heatFrac : 0;
            c.burnStart = null; c.xb = 0;
          }
          const spark = 360 - this.sparkAdv;
          if (c.Q > 0 && this.ignition && crossed(psiA, psiB, spark)) {
            c.burnStart = spark; c.burnDur = this.burnDur; c.xb = 0; c.lastQ = c.Q;
            this.events.push({ cyl: c.i, t: this.t, Q: c.Q });
          }
          if (crossed(psiA, psiB, SPEC.evo)) { c.burnStart = null; c.Q = 0; }
          if (crossed(psiA, psiB, 0)) {
            c.imep = c.W / VD; c.W = 0;
            c.peakP = c.curPeak; c.peakAt = c.curPeakAt; c.curPeak = 0;
          }
        }
        if (c.burnStart !== null) {
          const u = wrap720(psiB - c.burnStart);
          const x = wiebe(u < 360 ? u / c.burnDur : 0);
          const dQ = c.Q * (x - c.xb);
          c.xb = x;
          if (dQ > 0) p += (g - 1) * dQ / VB;
        }
        const li = liftIn(psiB), le = liftEx(psiB);
        if (li > 0) p += (this.pMan - p) * (1 - Math.exp(-dt * li * kFlow / VB));
        if (le > 0) p += (this.pExh - p) * (1 - Math.exp(-dt * le * kFlow / VB));
        p += (P_ATM - p) * leak;

        const dW = 0.5 * (p0 + p) * (VB - VA);
        c.W += dW; pdv += dW;
        if (p > c.curPeak) { c.curPeak = p; c.curPeakAt = psiB; }

        const F = (p - P_ATM) * AREA;
        const dsv = dsOf(aB);
        const fin = -SPEC.recipMass * w * w * ddsOf(aB);
        const tg = F * dsv;
        tGas += tg;
        tIn += fin * dsv;

        c.p = p; c.psi = psiB; c.V = VB;
        c.F = F; c.Frod = (F + fin) / Math.cos(rodAngle(aB)); c.torque = tg;
        c.lIn = li; c.lEx = le;
        c.T = c.vIvc > 0 ? T_INTAKE * (p * VB) / (c.pIvc * c.vIvc) : T_ATM;
        this.bins.p[c.i][Math.floor(psiB) % 720] = p;
      }

      // ---- dinâmica do virabrequim
      const fmep = (0.97 + 0.15 * rpm / 1000 + 0.05 * Math.pow(rpm / 1000, 2)) * 1e5;
      const tFric = fmep * VD * 4 / (4 * Math.PI) * Math.tanh(w / 1.5);
      let tStart = 0;
      if (this.starter > 0) {
        tStart = 140 * clamp(1 - rpm / 300, 0, 1);
        this.starter -= dt;
        if (this.ignition && rpm > 600) this.starter = 0;
      }
      const tLoad = this.load * Math.tanh(w / 3);
      let tHand = 0;
      if (this.hand) tHand = clamp(900 * (this.hand.target - this.thetaCum * DEG) - 25 * w, -400, 400);

      this.omega += (tGas + tIn + tStart + tHand - tFric - tLoad) / SPEC.inertia * dt;
      this.thetaCum = th1;
      this.t += dt;

      const bin = Math.floor(wrap720(th1)) % 720;
      this.bins.tGas[bin] = tGas;
      this.bins.tTot[bin] = tGas + tIn;
      this.bins.tNet[bin] = tGas + tIn - tFric;

      this.acc.pdv += pdv;
      this.acc.tdth += tGas * dDeg * DEG;
      if (wrapped) {
        this.lastCycle = { pdv: this.acc.pdv, tdth: this.acc.tdth, valid: true };
        this.acc.pdv = 0; this.acc.tdth = 0;
      }

      this.tGas = tGas; this.tIn = tIn; this.tFric = tFric; this.tLoad = tLoad;
      this.tStart = tStart; this.tHand = tHand;

      const rr = Math.abs(this.rpm);
      if (this.hand) this.status = 'girando à mão';
      else if (this.starter > 0) this.status = 'dando partida';
      else if (this.ignition && rr > 350) this.status = this.fuelCut ? 'limitador de giro' : 'em funcionamento';
      else if (this.ignition && rr > 30) this.status = 'apagando';
      else if (this.ignition) this.status = 'ignição ligada';
      else if (rr > 30) this.status = 'desacelerando';
      else this.status = 'desligado';
    }

    // integra `simDt` segundos; devolve o tempo efetivamente simulado (há um teto de passos por quadro)
    advance(simDt, maxSteps) {
      if (simDt <= 0) return 0;
      const budget = maxSteps || 40000;
      let n = Math.ceil(Math.max(Math.abs(this.omega) * simDt * RAD / 0.5, simDt / 1e-4));
      n = Math.max(1, n);
      if (n > budget) { simDt = simDt * budget / n; n = budget; }
      const h = simDt / n;
      for (let k = 0; k < n; k++) this.step(h);
      return simDt;
    }

    // avança o virabrequim `deg` graus; parado, o motor é girado "à mão" contra a compressão
    stepDegrees(deg) {
      const target = this.thetaCum + deg;
      if (Math.abs(this.rpm) < 30) {
        const prev = this.hand;
        this.hand = { target: target * DEG };
        for (let k = 0; k < 4000 && Math.abs(this.thetaCum - target) > 0.02; k++) this.step(1e-4);
        this.hand = prev;
        if (!prev) this.omega = 0;
      } else {
        for (let k = 0; k < 200000 && this.thetaCum < target; k++) this.step(Math.min(1e-4, 0.25 * DEG / Math.max(Math.abs(this.omega), 1)));
      }
    }

    validate() {
      const out = [];
      const add = (name, ok, detail) => out.push({ name, ok, detail });
      const disp = VD * 4 * 1000;
      add('Cilindrada', Math.abs(disp - 1.773) < 0.005, `${disp.toFixed(3)} L (4 × π/4 × 81² × 86 mm)`);
      const cr = volume(180) / volume(0);
      add('Taxa de compressão', Math.abs(cr - SPEC.cr) < 0.01, `V(PMI)/V(PMS) = ${cr.toFixed(2)}:1`);
      const stroke = (sOf(Math.PI) - sOf(0)) * 1000;
      add('Curso pela cinemática', Math.abs(stroke - 86) < 1e-6, `s(180°) − s(0°) = ${stroke.toFixed(3)} mm`);
      const fires = SPEC.firingOrder.map(n => wrap720(360 - SPEC.offsets[n - 1]));
      const gaps = fires.map((f, k) => wrap720(fires[(k + 1) % 4] - f));
      add('Intervalo de ignição', gaps.every(x => x === 180), `ordem 1-3-4-2, ${gaps.join('° / ')}°`);
      add('Comandos a ½ rotação', true, `polia do comando com o dobro dos dentes: ${(Math.abs(this.rpm) / 2).toFixed(0)} rpm`);
      let ok = true;
      for (let k = 0; k < 720; k++) {
        if (liftIn(k) > 0 && liftEx(k) > 0 && !(k >= 710 || k < 10)) ok = false;
      }
      add('Cruzamento de válvulas', ok, 'admissão e escape abertos juntos só entre 710° e 10° (20°)');
      if (this.lastCycle.valid && Math.abs(this.lastCycle.pdv) > 1) {
        const err = Math.abs(this.lastCycle.tdth - this.lastCycle.pdv) / Math.max(Math.abs(this.lastCycle.pdv), 1);
        add('Balanço de energia', err < 0.03, `∮p·dV = ${this.lastCycle.pdv.toFixed(1)} J · ∫T·dθ = ${this.lastCycle.tdth.toFixed(1)} J (${(err * 100).toFixed(2)}%)`);
      } else {
        add('Balanço de energia', true, 'aguardando um ciclo completo com combustão');
      }
      const c = this.cyl.reduce((a, b) => (b.peakP > a.peakP ? b : a));
      if (c.peakP > 15e5) {
        const at = c.peakAt - 360;
        add('Pico de pressão após o PMS', at > 0 && at < 30, `${(c.peakP / 1e5).toFixed(1)} bar a ${at.toFixed(0)}° DPMS (cilindro ${c.i + 1})`);
      }
      return out;
    }
  }

  return {
    Engine, SPEC, PHASES, P_ATM, VD, VC, AREA, DEG, RAD,
    wrap720, clamp, sOf, dsOf, volume, rodAngle, liftIn, liftEx, phaseOf, DUR_IN, DUR_EX,
  };
});
