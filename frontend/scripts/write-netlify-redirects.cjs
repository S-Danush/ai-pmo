'use strict';

const fs = require('fs');
const path = require('path');

const publicDir = path.join(__dirname, '..', 'public');
fs.mkdirSync(publicDir, { recursive: true });

const backend = (process.env.BACKEND_URL || '').replace(/\/$/, '');
const lines = [];
if (backend) {
  lines.push(`/api/*  ${backend}/api/:splat  200`);
}
lines.push('/*  /index.html  200');
fs.writeFileSync(path.join(publicDir, '_redirects'), `${lines.join('\n')}\n`, 'utf8');
