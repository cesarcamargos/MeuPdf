// Generates a bcrypt hash for AUTH_PASSWORD_HASH.
// Usage: npm run hash -- "your-password"   (or: node scripts/hash-password.mjs "your-password")
import bcrypt from 'bcryptjs';

const password = process.argv[2];
if (!password) {
  console.error('Usage: node scripts/hash-password.mjs "<password>"');
  process.exit(1);
}
console.log(bcrypt.hashSync(password, 10));
