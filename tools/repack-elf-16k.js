#!/usr/bin/env node
/*
 * Repacks a prebuilt ELF shared library so every PT_LOAD segment is congruent
 * modulo 16 KiB (p_vaddr === p_offset mod 16384) and declares p_align = 16384.
 *
 * Android devices running a 16 KiB page-size kernel refuse to load libraries
 * whose load segments are only 4 KiB aligned, which shows up as an
 * UnsatisfiedLinkError / instant crash. 16 KiB-aligned libraries still load on
 * 4 KiB devices, so the result is compatible with both.
 *
 * Usage: node repack-elf-16k.js <path-to-.so>
 */
'use strict';
const fs = require('fs');

const PAGE = 16384;
const file = process.argv[2];
if (!file) { console.error('usage: repack-elf-16k.js <file.so>'); process.exit(2); }

const buf = fs.readFileSync(file);
if (buf.readUInt32LE(0) !== 0x464c457f) { console.error('not an ELF file: ' + file); process.exit(2); }
const is64 = buf[4] === 2;
if (buf[5] !== 1) { console.error('only little-endian is supported'); process.exit(2); }

const PH = is64
  ? { size: 56, type: 0, off: 8, vaddr: 16, filesz: 32, memsz: 40, align: 48 }
  : { size: 32, type: 0, off: 4, vaddr: 8, filesz: 16, memsz: 20, align: 28 };

const phoff = is64 ? Number(buf.readBigUInt64LE(0x20)) : buf.readUInt32LE(0x1c);
const phentsize = is64 ? buf.readUInt16LE(0x36) : buf.readUInt16LE(0x2a);
const phnum = is64 ? buf.readUInt16LE(0x38) : buf.readUInt16LE(0x2c);
if (phentsize !== PH.size && phentsize !== 0) { /* tolerate */ }

const rd64 = o => Number(buf.readBigUInt64LE(o));
const rd32 = o => buf.readUInt32LE(o);
const get = (o, is64Field) => is64 ? rd64(o) : rd32(o);

const phdrs = [];
for (let i = 0; i < phnum; i++) {
  const p = phoff + i * phentsize;
  phdrs.push({
    index: i, base: p,
    type: buf.readUInt32LE(p + (is64 ? 0 : 0)),
    off: get(p + PH.off),
    vaddr: get(p + PH.vaddr),
    filesz: get(p + PH.filesz),
    memsz: get(p + PH.memsz),
    align: get(p + PH.align)
  });
}

const loads = phdrs.filter(p => p.type === 1).sort((a, b) => a.off - b.off);
if (!loads.length) { console.error('no PT_LOAD segments'); process.exit(2); }

// already 16K aligned?
let already = loads.every(s => (s.vaddr - s.off) % PAGE === 0 && s.align >= PAGE);
if (already) { console.log('already 16 KiB aligned, nothing to do: ' + file); process.exit(0); }

// compute new file offsets
let cursor = 0;
const chunks = [];
for (const s of loads) {
  if (s.off !== 0) {
    const r = ((s.vaddr - cursor) % PAGE + PAGE) % PAGE;
    const target = cursor + r;
    if (target > cursor) chunks.push({ pad: target - cursor });
    cursor = target;
  } else {
    if (cursor !== 0) { chunks.push({ pad: -1 }); }
    cursor = 0;
  }
  chunks.push({ src: s.off, len: s.filesz });
  s.newOff = cursor;
  cursor += s.filesz;
}
const totalSize = cursor;

const out = Buffer.alloc(totalSize);
let w = 0;
for (const c of chunks) {
  if (c.pad !== undefined) { if (c.pad > 0) w += c.pad; continue; } // leaves zero padding
  buf.copy(out, w, c.src, c.src + c.len);
  w += c.len;
}
if (w !== totalSize) { console.error('internal size mismatch'); process.exit(2); }

// remap program headers
function remapOffset(oldOff, oldFilesz) {
  for (const s of loads) {
    if (oldOff >= s.off && oldOff <= s.off + s.filesz) {
      return s.newOff + (oldOff - s.off);
    }
  }
  return null;
}
const patch64 = (o, v) => out.writeBigUInt64LE(BigInt(v), o);
const patch32 = (o, v) => out.writeUInt32LE(v >>> 0, o);
for (const p of phdrs) {
  if (p.type === 1) {
    if (is64) { patch64(p.base + PH.off, p.newOff); patch64(p.base + PH.align, PAGE); }
    else { patch32(p.base + PH.off, p.newOff); patch32(p.base + PH.align, PAGE); }
    continue;
  }
  if (p.off === 0) continue;
  const no = remapOffset(p.off, p.filesz);
  if (no === null) { console.error('cannot remap program header type ' + p.type + ' at ' + p.off); process.exit(2); }
  if (is64) patch64(p.base + PH.off, no); else patch32(p.base + PH.off, no);
}

// drop the section header table (offsets are stale after repacking)
const shoffField = is64 ? 0x28 : 0x20;
if (is64) out.writeBigUInt64LE(0n, shoffField); else out.writeUInt32LE(0, shoffField);
out.writeUInt16LE(0, is64 ? 0x3c : 0x30); // e_shnum
out.writeUInt16LE(0, is64 ? 0x3e : 0x32); // e_shstrndx

fs.writeFileSync(file, out);
console.log('repacked ' + file + ': ' + buf.length + ' -> ' + out.length + ' bytes, ' +
  loads.length + ' PT_LOAD segments aligned to ' + PAGE);
