/**
 * Register Page — Form for creating a new account.
 *
 * Flow:
 * 1. User fills in username + email + password
 * 2. Form submits → POST /api/users/register via axios
 * 3. On success → redirect to /login (user logs in with new account)
 * 4. On error → display error message (e.g., "Email already registered")
 *
 * Note: We don't auto-login after registration — redirect to login
 * so the user confirms their account was created.
 */
import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import axios from 'axios';

function Register() {
  // Controlled form inputs — one useState per form field
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  // For redirecting to /login after successful registration
  const navigate = useNavigate();

  /** Handle form submission — sends registration data to backend */
  const handleSubmit = async (e) => {
    e.preventDefault();  // Prevent page reload
    setError('');        // Clear previous errors

    try {
      // POST to backend — sends { username, email, password } as JSON
      await axios.post('/api/users/register', { username, email, password });
      navigate('/login');  // Registration successful — go to login page
    } catch (err) {
      // Show server error message or generic fallback
      setError(err.response?.data?.error || 'Registration failed');
    }
  };

  return (
    <div className="page">
      <h1>Register</h1>

      {/* Show error message if registration failed */}
      {error && <p className="error-message">{error}</p>}

      <form onSubmit={handleSubmit}>
        <div>
          <label>Username</label>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </div>
        <div>
          <label>Email</label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div>
          <label>Password</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        <button type="submit">Register</button>
      </form>

      <p>Already have an account? <Link to="/login">Login</Link></p>
    </div>
  );
}

export default Register;
