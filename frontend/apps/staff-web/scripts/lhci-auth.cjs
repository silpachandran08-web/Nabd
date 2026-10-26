// OPS-039: Lighthouse CI hook. Staff routes need a session, so the preview's synthetic login token (PERF_ACCESS_TOKEN,
// minted by the job from the seed logins, never committed) is put in localStorage before each audit. /login runs signed out.
module.exports = async (browser, context) => {
  if (!process.env.PERF_ACCESS_TOKEN || new URL(context.url).pathname === "/login") return;
  const page = await browser.newPage();
  await page.goto(new URL("/login", context.url).href);
  await page.evaluate((t) => localStorage.setItem("nabd_access_token", t), process.env.PERF_ACCESS_TOKEN);
  await page.close();
};
