'use strict';
// Create Windows ICO from the generated LUMAVUN PNG without new dependencies.
// Windows ICO format supports PNG-compressed image payloads.
const fs=require('node:fs'),path=require('node:path');
const source=path.join(__dirname,'..','assets','app-icon.png');
const target=path.join(__dirname,'..','assets','lumavun.ico');
const png=fs.readFileSync(source);
if(png.subarray(0,8).toString('hex')!=='89504e470d0a1a0a')throw Error('Invalid PNG application icon.');
const width=png.readUInt32BE(16),height=png.readUInt32BE(20);
if(width!==height||width<16||width>256)throw Error('App icon must be square 16..256px.');
const header=Buffer.alloc(22);
header.writeUInt16LE(0,0);header.writeUInt16LE(1,2);header.writeUInt16LE(1,4);
header.writeUInt8(width===256?0:width,6);header.writeUInt8(height===256?0:height,7);
header.writeUInt8(0,8);header.writeUInt8(0,9);
header.writeUInt16LE(1,10);header.writeUInt16LE(32,12);
header.writeUInt32LE(png.length,14);header.writeUInt32LE(22,18);
fs.writeFileSync(target,Buffer.concat([header,png]));
console.log('LUMAVUN Windows icon generated from the LUMAVUN brand artwork.');
