'use strict';
// Generate valid multi-resolution Windows ICO from the original generated
// LUMAVUN icon without installing any fonts, image tools or npm dependencies.
const fs=require('node:fs'),path=require('node:path'),zlib=require('node:zlib');
const source=path.join(__dirname,'..','assets','app-icon.png');
const target=path.join(__dirname,'..','assets','lumavun.ico');
const original=fs.readFileSync(source);
const SIG=Buffer.from('89504e470d0a1a0a','hex');
if(!original.subarray(0,8).equals(SIG))throw Error('Invalid app icon PNG.');
function crc32(data){
 let crc=0xffffffff;
 for(const v of data){crc^=v;for(let i=0;i<8;i++)crc=(crc>>>1)^(0xedb88320&-(crc&1));}
 return (crc^0xffffffff)>>>0;
}
function chunk(type,content){
 const tag=Buffer.from(type,'ascii'),len=Buffer.alloc(4);
 len.writeUInt32BE(content.length);
 const checksum=Buffer.alloc(4);
 checksum.writeUInt32BE(crc32(Buffer.concat([tag,content])));
 return Buffer.concat([len,tag,content,checksum]);
}
let off=8,width,height,bitDepth,colorType,interlace,palette=null,transparency=null,compressed=[];
while(off<original.length){
 const size=original.readUInt32BE(off),type=original.toString('ascii',off+4,off+8);
 const data=original.subarray(off+8,off+8+size);
 if(type==='IHDR'){
  width=data.readUInt32BE(0);height=data.readUInt32BE(4);
  bitDepth=data[8];colorType=data[9];interlace=data[12];
 }
 if(type==='PLTE')palette=data;
 if(type==='tRNS')transparency=data;
 if(type==='IDAT')compressed.push(data);
 off+=size+12;
 if(type==='IEND')break;
}
if(width!==64||height!==64||bitDepth!==8||colorType!==3||interlace!==0||!palette)
 throw Error('LUMAVUN source icon must be a 64x64, 8-bit non-interlaced palette PNG.');
const packed=zlib.inflateSync(Buffer.concat(compressed));
const rows=[];let p=0,prior=Buffer.alloc(width);
for(let y=0;y<height;y++){
 const filter=packed[p++],row=Buffer.alloc(width);
 for(let x=0;x<width;x++){
  const value=packed[p++],a=x?row[x-1]:0,b=prior[x],c=x?prior[x-1]:0;
  let pred=0;
  if(filter===1)pred=a;
  if(filter===2)pred=b;
  if(filter===3)pred=Math.floor((a+b)/2);
  if(filter===4){
   const pa=Math.abs(b-c),pb=Math.abs(a-c),pc=Math.abs(a+b-2*c);
   pred=pa<=pb&&pa<=pc?a:pb<=pc?b:c;
  }
  if(filter>4)throw Error('Unsupported PNG filter.');
  row[x]=(value+pred)&255;
 }
 rows.push(row);prior=row;
}
const pixel=(x,y)=>{
 const index=rows[Math.max(0,Math.min(63,y))][Math.max(0,Math.min(63,x))];
 return [palette[index*3]||0,palette[index*3+1]||0,palette[index*3+2]||0,
  transparency&&index<transparency.length?transparency[index]:255];
};
const dimension=256,raw=Buffer.alloc(dimension*(1+dimension*4));
for(let y=0;y<dimension;y++){
 const fy=(y+.5)/4-.5,y0=Math.floor(fy),ty=fy-y0;
 let output=y*(1+dimension*4);raw[output++]=0;
 for(let x=0;x<dimension;x++){
  const fx=(x+.5)/4-.5,x0=Math.floor(fx),tx=fx-x0;
  const a=pixel(x0,y0),b=pixel(x0+1,y0),c=pixel(x0,y0+1),d=pixel(x0+1,y0+1);
  for(let i=0;i<4;i++){
   const top=a[i]*(1-tx)+b[i]*tx,bot=c[i]*(1-tx)+d[i]*tx;
   raw[output++]=Math.round(top*(1-ty)+bot*ty);
  }
 }
}
const ihdr=Buffer.alloc(13);
ihdr.writeUInt32BE(dimension,0);ihdr.writeUInt32BE(dimension,4);ihdr[8]=8;ihdr[9]=6;
const bigPng=Buffer.concat([SIG,chunk('IHDR',ihdr),
 chunk('IDAT',zlib.deflateSync(raw,{level:9})),chunk('IEND',Buffer.alloc(0))]);
const entries=[{png:original,dim:64},{png:bigPng,dim:256}];
const head=Buffer.alloc(6+entries.length*16);
head.writeUInt16LE(1,2);head.writeUInt16LE(entries.length,4);
let offset=head.length;
for(let i=0;i<entries.length;i++){
 const {png,dim}=entries[i],pos=6+i*16;
 head[pos]=dim===256?0:dim;head[pos+1]=dim===256?0:dim;
 head.writeUInt16LE(1,pos+4);head.writeUInt16LE(32,pos+6);
 head.writeUInt32LE(png.length,pos+8);head.writeUInt32LE(offset,pos+12);
 offset+=png.length;
}
fs.writeFileSync(target,Buffer.concat([head,...entries.map(x=>x.png)]));
console.log('LUMAVUN multi-resolution Windows icon generated (64px and 256px).');
