import sys, asyncio
from playwright.async_api import async_playwright
async def main(svg, out, w, h):
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=None)
        pg = await b.new_page(viewport={'width': w, 'height': h})
        await pg.set_content(f"<html><body style='margin:0;background:transparent'>{open(svg).read()}</body></html>")
        await pg.screenshot(path=out, omit_background=True)
        await b.close()
asyncio.run(main(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])))
