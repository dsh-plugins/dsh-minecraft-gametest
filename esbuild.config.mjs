import { build } from 'esbuild'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'

await build({
  entryPoints: ['src/client/index.tsx'],
  outfile: 'lib/client.js',
  bundle: true,
  format: 'cjs',
  platform: 'browser',
  target: 'es2022',
  jsx: 'automatic',
  external: ['react', 'react/*', 'react-dom', 'react-dom/*', '@dsh-plugin/dsh-loader/client'],
  define: { 'process.env.NODE_ENV': '"production"' },
  banner: { js: 'window.__ModuleLoader__.load({ id: "@dsh-plugin/dsh-minecraft-gametest", factory: (require) => { var module = { exports: {} }; var exports = module.exports;' },
  footer: { js: 'return module.exports; } });' },
})

// XMCL unzip 2.2.0 publishes its compiled JS but points Node at a missing source file.
await build({
  entryPoints: ['src/installer-deps.ts'],
  outfile: 'lib/installer-deps.js',
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node24',
  mainFields: ['module', 'main'],
  external: ['undici'],
  banner: { js: "import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);" },
  plugins: [{
    name: 'xmcl-unzip-entry',
    setup(builder) {
      builder.onResolve({ filter: /^@xmcl\/unzip$/ }, ({ importer }) => {
        const manifest = createRequire(importer).resolve('@xmcl/unzip/package.json')
        return { path: join(dirname(manifest), 'dist/index.mjs') }
      })
    },
  }],
})
