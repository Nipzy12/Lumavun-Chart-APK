const fs = require('node:fs');
const path = require('node:path');
const input = path.join(__dirname, '..', 'node_modules', 'lightweight-charts', 'dist', 'lightweight-charts.standalone.production.js');
const output = path.join(__dirname, '..', 'renderer', 'vendor', 'lightweight-charts.js');
if (!fs.existsSync(input)) {
  throw new Error('Cannot find Lightweight Charts standalone bundle: ' + input);
}
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.copyFileSync(input, output);
console.log('Bundled local chart library (no runtime CDN required).');
