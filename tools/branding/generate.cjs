// Opt-in asset authoring; Sharp is a separately supplied tool, not an app dependency.
// Usage: node tools/branding/generate.cjs <path-to-sharp-module>
const fs = require('node:fs');
const path = require('node:path');
if (!process.argv[2]) throw new Error('SupplySharpModulePath');
const sharp = require(path.resolve(process.argv[2]));
const root = path.resolve(__dirname, '../..');
const res = path.join(root, 'app/src/main/res');
const source = fs.readFileSync(path.join(root, 'docs/branding/dd-player.svg'), 'utf8');
const write = (file, data) => fs.writeFileSync(path.join(res, file), data);
// Keep the approved mark, remove its tile, and inset it into the adaptive safe area.
const foreground = source.replace(/<rect width="108" height="108" fill="url\(#[^"]+bg\)"\/>/, '')
  .replace('<g clip-path=', '<g transform="translate(54 54) scale(.8) translate(-54 -54)" clip-path=');
const paths = [...source.matchAll(/<path[^>]* d="([^"]+)"/g)].map(m => m[1]);
if (paths.length !== 2 || foreground === source) throw new Error('UnexpectedArtworkStructure');
const mono = `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:pivotX="54" android:pivotY="54" android:scaleX="0.8" android:scaleY="0.8">
        <group android:pivotX="58" android:pivotY="54" android:scaleX="0.875" android:scaleY="0.875">
            <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="${paths[0]}" />
            <path android:fillColor="#FFFFFFFF" android:pathData="${paths[1]}" />
        </group>
    </group>
</vector>
`;
(async () => {
  for (const [density, size, fore] of [['mdpi',48,108], ['hdpi',72,162], ['xhdpi',96,216], ['xxhdpi',144,324], ['xxxhdpi',192,432]]) {
    await sharp(Buffer.from(source)).resize(size,size).png().toFile(path.join(res, `mipmap-${density}/ic_launcher.png`));
    await sharp(Buffer.from(foreground)).resize(fore,fore).png().toFile(path.join(res, `mipmap-${density}/ic_launcher_foreground.png`));
  }
  write('drawable/ic_launcher_monochrome.xml', mono);
  const banner = `<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180"><rect width="320" height="180" fill="#101827"/><svg x="106" y="24" width="108" height="108" viewBox="0 0 108 108">${source.replace(/^<svg[^>]*>|<\/svg>$/g, '')}</svg><text x="160" y="158" text-anchor="middle" fill="#F0F6FF" font-family="Segoe UI, sans-serif" font-size="18" font-weight="600">DriveDeck Player</text></svg>`;
  fs.writeFileSync(path.join(root, 'docs/branding/tv-banner.svg'), banner);
  await sharp(Buffer.from(banner)).png().toFile(path.join(res, 'mipmap-xhdpi/tv_banner.png'));
  console.log('Generated legacy, adaptive, monochrome and TV artwork.');
})().catch(e => { console.error(e); process.exitCode = 1; });
