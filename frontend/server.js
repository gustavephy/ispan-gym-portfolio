import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const distDir = path.join(__dirname, 'dist')

const BACKEND_HOST = process.env.BACKEND_HOST || 'backend'
const BACKEND_PORT = process.env.BACKEND_PORT || 8080

const server = http.createServer((req, res) => {
  const isApi =
    req.url.startsWith('/api') ||
    req.url.startsWith('/oauth2') ||
    req.url.startsWith('/uploads') ||
    req.url.startsWith('/ws-chat')

  if (isApi) {
    const options = {
      hostname: BACKEND_HOST,
      port: BACKEND_PORT,
      path: req.url,
      method: req.method,
      headers: { ...req.headers, host: `${BACKEND_HOST}:${BACKEND_PORT}` },
    }
    const proxyReq = http.request(options, (proxyRes) => {
      res.writeHead(proxyRes.statusCode, proxyRes.headers)
      proxyRes.pipe(res, { end: true })
    })
    proxyReq.on('error', (err) => {
      res.writeHead(502, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ error: 'Bad Gateway', details: err.message }))
    })
    req.pipe(proxyReq, { end: true })
    return
  }

  let filePath = path.join(distDir, req.url.split('?')[0])
  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    filePath = path.join(distDir, 'index.html')
  }

  const ext = path.extname(filePath)
  const mimeTypes = {
    '.html': 'text/html',
    '.js': 'text/javascript',
    '.css': 'text/css',
    '.json': 'application/json',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.gif': 'image/gif',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.woff2': 'font/woff2',
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(500)
      res.end('Server Error')
    } else {
      res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' })
      res.end(data)
    }
  })
})

server.listen(80, () => {
  console.log('Frontend server listening on port 80')
})
