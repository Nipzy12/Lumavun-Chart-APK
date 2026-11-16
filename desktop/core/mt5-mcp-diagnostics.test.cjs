'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {explainMcpError}=require('./mt5-mcp-diagnostics.cjs');
test('Python bridge import error explains how to use native MT5 MCP without Python',()=>{
 const msg=explainMcpError('LUMAVUN_BRIDGE',
  new Error('MT5 MCP tool error: MT5: MetaTrader5 Python package missing. Install MetaTrader5 for this Python environment.'));
 assert.match(msg,/Gateway LUMAVUN/);
 assert.match(msg,/BUKAN MCP native MT5/);
 assert.match(msg,/Hubungkan URL \+ Key/);
 assert.match(msg,/py -3 -m pip install MetaTrader5/);
});
test('native MT5 MCP errors are not inaccurately labeled as missing Python',()=>{
 const msg=explainMcpError('MT5_NATIVE',new Error('MT5 native MCP rejected account request.'));
 assert.equal(msg,'MT5 native MCP rejected account request.');
});
test('generic gateway errors remain visible without inventing Python failure',()=>{
 assert.equal(explainMcpError('LUMAVUN_BRIDGE',new Error('MT5 terminal is offline.')),'MT5 terminal is offline.');
});
