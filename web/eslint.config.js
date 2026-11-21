// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = defineConfig([
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        {
          type: 'attribute',
          prefix: 'app',
          style: 'camelCase',
        },
      ],
      '@angular-eslint/component-selector': [
        'error',
        {
          type: 'element',
          prefix: 'app',
          style: 'kebab-case',
        },
      ],
      // nginx sends a Content-Security-Policy with style-src 'self': styles come from
      // styles.css and nowhere else. Angular would put the styles of a component into a
      // <style> tag, which the browser then ignores.
      'no-restricted-syntax': [
        'error',
        {
          selector: "Decorator[expression.callee.name='Component'] Property[key.name=/^(styles|styleUrl|styleUrls)$/]",
          message: 'Put the styles in src/styles.css: the Content-Security-Policy in nginx.conf blocks the inline styles of a component.',
        },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {
      // A style attribute is blocked by the Content-Security-Policy in nginx.conf. A
      // [style.width.%] binding is set through the DOM and is allowed.
      '@angular-eslint/template/no-inline-styles': ['error', { allowNgStyle: false, allowBindToStyle: true }],
    },
  },
]);
