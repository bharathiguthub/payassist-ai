import { configureAxe } from 'vitest-axe';

/** axe-core for jsdom: colour contrast needs real rendering (canvas), so the browser suite checks it instead. */
export const axe = configureAxe({ rules: { 'color-contrast': { enabled: false } } });
