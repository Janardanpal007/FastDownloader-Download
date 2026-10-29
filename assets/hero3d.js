// FastDownloader hero: a glossy black "pixel" download arrow, lit red from behind
// like a car in a studio, that turns slowly and leans toward the pointer. Falls back to nothing if WebGL is unavailable.
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';

const canvas = document.getElementById('hero-3d');
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// Arrow drawn on a pixel grid: # is a block.
const ART = [
  '..#..',
  '..#..',
  '..#..',
  '#####',
  '.###.',
  '..#..',
  '.....',
  '#####',
];

function buildArrow() {
  const parts = [];
  const rows = ART.length, cols = ART[0].length;
  ART.forEach((row, y) => {
    [...row].forEach((c, x) => {
      if (c !== '#') return;
      const g = new RoundedBoxGeometry(1, 1, 1.4, 4, 0.18);
      g.translate(x - (cols - 1) / 2, (rows - 1) / 2 - y, 0);
      parts.push(g);
    });
  });
  return mergeGeometries(parts);
}

function start() {
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  } catch (e) {
    return;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  renderer.toneMapping = THREE.ACESFilmicToneMapping;

  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.35;

  const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 100);
  camera.position.set(0, 0, 22);

  const material = new THREE.MeshPhysicalMaterial({
    color: 0x070707, metalness: 0.35, roughness: 0.2, clearcoat: 1, clearcoatRoughness: 0.05,
  });
  const arrow = new THREE.Mesh(buildArrow(), material);
  arrow.rotation.set(0.25, -0.6, 0.05);
  scene.add(arrow);

  // White strip lights above, red glow behind and below for the rim highlights.
  const key = new THREE.DirectionalLight(0xffffff, 0.9);
  key.position.set(2, 8, 6);
  scene.add(key);
  const back = new THREE.PointLight(0xff2d1f, 260, 40);
  back.position.set(0, 2, -6);
  scene.add(back);
  const left = new THREE.PointLight(0xff3a20, 120, 30);
  left.position.set(-7, -2, 3);
  scene.add(left);
  const right = new THREE.PointLight(0xff3a20, 120, 30);
  right.position.set(7, -2, 3);
  scene.add(right);

  function resize() {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    // Back the camera off until the whole arrow (5 x 8 blocks plus margin) fits.
    const half = Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
    camera.position.z = Math.max(5.5 / half, 3.8 / (half * camera.aspect));
    camera.updateProjectionMatrix();
  }
  resize();
  window.addEventListener('resize', resize);

  if (reduceMotion) {
    renderer.render(scene, camera);
    return;
  }

  const pointer = { x: 0, y: 0 };
  window.addEventListener('pointermove', (e) => {
    pointer.x = e.clientX / window.innerWidth - 0.5;
    pointer.y = e.clientY / window.innerHeight - 0.5;
  });

  // Only animate while the hero is on screen.
  let visible = true;
  new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; }).observe(canvas);

  const clock = new THREE.Clock();
  renderer.setAnimationLoop(() => {
    if (!visible) return;
    const t = clock.getElapsedTime();
    const targetY = Math.sin(t * 0.45) * 0.7 + pointer.x * 0.8;
    const targetX = 0.2 + pointer.y * 0.5;
    arrow.rotation.y += (targetY - arrow.rotation.y) * 0.05;
    arrow.rotation.x += (targetX - arrow.rotation.x) * 0.05;
    arrow.position.y = Math.sin(t * 0.9) * 0.25;
    renderer.render(scene, camera);
  });
}

start();
