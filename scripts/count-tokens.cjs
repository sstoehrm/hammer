// usage: node scripts/count-tokens.cjs <re-frame.cljs> <hammer.cljs>
const { readFileSync } = require("node:fs");
const { countTokens } = require("@anthropic-ai/tokenizer");

const [rf, ours] = process.argv.slice(2).map((f) => countTokens(readFileSync(f, "utf8")));
const saving = 1 - ours / rf;
console.log(`re-frame TodoMVC: ${rf} tokens`);
console.log(`hammer   TodoMVC: ${ours} tokens`);
console.log(`saving: ${(saving * 100).toFixed(1)}% (target >= 30%)`);
process.exit(saving >= 0.3 ? 0 : 1);
