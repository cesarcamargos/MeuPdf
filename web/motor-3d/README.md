# Motor DOHC 16V — simulador 3D

Animação 3D interativa de um motor 4 cilindros em linha DOHC 16V (1,77 L, Ø81 × 86 mm, biela 140 mm,
TC 10,5:1, ignição 1-3-4-2), feita com Three.js r128 e um modelo físico próprio.

- `sim.js` — física, sem dependências: cinemática biela-manivela, ciclo termodinâmico por cilindro com
  liberação de calor de Wiebe, gás residual, coletor de admissão com borboleta e controle de marcha lenta,
  atrito (FMEP), inércia, motor de partida, limitador e giro à mão.
- `app.js` — cena 3D (vista em corte, raio X, vista explodida), instrumentos, gráficos e controles.
- `index.html` — layout e estilos.

Para abrir localmente, sirva a pasta por HTTP (o Three.js vem do cdnjs):

```sh
cd web/motor-3d && python3 -m http.server 8000
# abra http://localhost:8000
```

A física pode ser testada no Node: `node -e "const S=require('./sim.js'); const e=new S.Engine(); e.start(); e.advance(3); console.log(e.rpm)"`.
