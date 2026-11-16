'use strict';
/*
 * Distinguish our optional Python-backed gateway from MetaTrader 5's
 * built-in MCP terminal. Never expose API keys in diagnostics.
 */
function explainMcpError(mode,error){
 const original=String(error?.message||error||'Unknown MCP error').slice(0,500);
 if(mode==='LUMAVUN_BRIDGE'&&
  /MetaTrader5 Python package missing|Could not launch Python|Missing Python module|No module named ['"]?MetaTrader5/i.test(original)){
  return 'Koneksi ini memakai MCP Gateway LUMAVUN (bridge Python), BUKAN MCP native MT5. '+
   'Bridge ini tidak menemukan modul MetaTrader5. '+
   'Untuk MCP bawaan MT5 tanpa Python: Putus Koneksi → Gunakan Alamat MCP MT5 → '+
   'masukkan API key terbaru dari terminal → Hubungkan URL + Key → Uji Data Akun MCP. '+
   'JANGAN gunakan Hubungkan Server Lokal. '+
   'Jika sengaja memakai Gateway LUMAVUN, pasang modul pada Python yang digunakannya: py -3 -m pip install MetaTrader5.';
 }
 return original;
}
module.exports={explainMcpError};
