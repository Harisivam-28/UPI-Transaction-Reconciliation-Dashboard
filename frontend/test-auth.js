import puppeteer from 'puppeteer';

(async () => {
  const browser = await puppeteer.launch({ headless: 'new' });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800 });

  page.on('console', msg => console.log('PAGE LOG:', msg.text()));

  try {
    console.log('Navigating to http://localhost:5174/signup');
    await page.goto('http://localhost:5174/signup', { waitUntil: 'networkidle0' });
    
    // Take a screenshot of the Sign Up page
    await page.screenshot({ path: '/Users/hari/.gemini/antigravity-ide/brain/eb095eaa-517f-49a0-b776-d8f727d943ea/signup-page.png' });
    console.log('Took screenshot of Sign Up page');

    // Fill out the form
    console.log('Filling out form...');
    const inputs = await page.$$('.onboarding__input');
    await inputs[0].type('Test Business');
    await inputs[1].type(`test${Date.now()}@example.com`);
    await inputs[2].type('password123');

    await page.screenshot({ path: '/Users/hari/.gemini/antigravity-ide/brain/eb095eaa-517f-49a0-b776-d8f727d943ea/before-submit.png' });

    // Click Sign Up button
    console.log('Clicking Sign Up...');
    await page.click('button[type="submit"]');

    // Wait for redirect to onboarding
    console.log('Waiting for redirect to /onboarding...');
    await page.waitForSelector('.onboarding__chooser', { timeout: 10000 }).catch(async (e) => {
      console.log("Error waiting. Current error text on page:");
      const errorText = await page.evaluate(() => document.querySelector('.onboarding__error')?.textContent);
      console.log("Error element text:", errorText);
      await page.screenshot({ path: '/Users/hari/.gemini/antigravity-ide/brain/eb095eaa-517f-49a0-b776-d8f727d943ea/error-page.png' });
      throw e;
    });

    // Take screenshot of the Onboarding page
    await page.screenshot({ path: '/Users/hari/.gemini/antigravity-ide/brain/eb095eaa-517f-49a0-b776-d8f727d943ea/onboarding-page.png' });
    console.log('Took screenshot of Onboarding page. Success!');

  } catch (err) {
    console.error('Test failed:', err);
  } finally {
    await browser.close();
  }
})();
