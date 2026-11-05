import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import prettier from 'eslint-config-prettier';
import globals from 'globals';

// Hard deletes are forbidden (CLAUDE.md §9). Use dml.softDelete instead.
const NO_HARD_DELETE = 'Hard DELETE is forbidden. Use softDelete() from core/db/dml.';

export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**', 'docs/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: { globals: { ...globals.node } },
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/consistent-type-imports': 'error',
      'no-console': ['error', { allow: ['error'] }],
      'no-restricted-syntax': [
        'error',
        { selector: String.raw`Literal[value=/\bdelete\s+from\b/i]`, message: NO_HARD_DELETE },
        {
          selector: String.raw`TemplateElement[value.raw=/\bdelete\s+from\b/i]`,
          message: NO_HARD_DELETE,
        },
        { selector: String.raw`Literal[value=/\btruncate\s+table\b/i]`, message: NO_HARD_DELETE },
        { selector: String.raw`Literal[value=/\bdrop\s+table\b/i]`, message: NO_HARD_DELETE },
      ],
    },
  },
  {
    files: ['scripts/**', 'tests/**'],
    rules: { 'no-console': 'off' },
  },
  {
    // Admin panel browser scripts: plain ES5-style scripts, no bundler.
    files: ['public/**/*.js'],
    languageOptions: { sourceType: 'script', globals: { ...globals.browser } },
  },
  prettier,
);
