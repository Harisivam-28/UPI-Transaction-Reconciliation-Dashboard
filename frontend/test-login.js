import puppeteer from 'puppeteer';

(async () => {
  const browser = await puppeteer.launch({ headless: 'new' });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  page.on('console', msg => console.log('PAGE LOG:', msg.text()));
  page.on('pageerror', err => console.log('PAGE ERROR:', err.toString()));
  page.on('requestfailed', request => console.log('REQUEST FAILED:', request.url(), request.failure().errorText));

  try {
    console.log('Navigating to http://localhost:5174/login');
    const response = await page.goto('http://localhost:5174/login', { waitUntil: 'networkidle0' });
    console.log('HTTP Status:', response.status());
    
    await page.screenshot({ path: '/Users/hari/.gemini/antigravity-ide/brain/eb095eaa-517f-49a0-b776-d8f727d943ea/login-page.png' });
    console.log('Took screenshot of Login page');

    // Create a dummy user via curl to the backend just in case
    console.log('Filling out form...');
    const inputs = await page.$$('.onboarding__input');
    if (inputs.length >= 2) {
      await inputs[0].type('test1@example.com');
      await inputs[1].type('test');
      
      await page.screenshot({ path: '/Users/hari/.gemini/antigravity-ide/brain/eb095eaa-517f-49a0-b776-d8f727d943ea/login-before-submit.png' });
      console.log('Clicking Login...');
      await page.click('button[type="submit"]');

      console.log('Waiting for redirect...');
      await page.waitForNavigation({ waitUntil: 'networkidle0', timeout: 10000 }).catch(e => console.log("Timeout waiting for nav"));
      await page.screenshot({ path: '/Users/hari/.gemini/antigravity-ide/brain/eb095eaa-517f-49a0-b776-d8f727d943ea/login-after.png' });
    } else {
      console.log('Login inputs not found! HTML:');
      console.log(await page.content());
    }
  } catch (err) {
    console.error('Test failed:', err);
  } finally {
    await browser.close();
  }
})();
