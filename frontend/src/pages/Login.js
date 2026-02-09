/**
 * Login Page — Form for user authentication.
 *
 * Flow:
 * 1. User fills in email + password
 * 2. Form submits → POST /api/users/login via axios
 * 3. Backend returns JWT token
 * 4. Token saved to AuthContext (login function) + localStorage
 * 5. User redirected to /mylist
 */
import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '../context/AuthContext';

function Login() {
  // Controlled form inputs — React state drives the input values
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  // login() from AuthContext — saves token to state + localStorage
  const { login } = useAuth();

  // useNavigate() — programmatic navigation (redirect after login)
  const navigate = useNavigate();

  /**
   * Handle form submission.
   * async because we're making an API call with await.
   */
  const handleSubmit = async (e) => {
    e.preventDefault();  // Prevent default HTML form behavior (page reload)
    setError('');        // Clear any previous error messages

    try {
      // POST to backend — axios auto-converts object to JSON
      const { data } = await axios.post('/api/users/login', { email, password });

      login(data.token);    // Save JWT token via AuthContext
      navigate('/mylist');   // Redirect to anime list page
    } catch (err) {
      // err.response?.data?.error — optional chaining to safely access nested error
      // If server returned an error message, show it; otherwise show generic message
      setError(err.response?.data?.error || 'Login failed');
    }
  };

  return (
    <div className="page">
      <h1>Login</h1>

      {/* Show error message if login failed */}
      {error && <p className="error-message">{error}</p>}

      {/* onSubmit calls handleSubmit when form is submitted */}
      <form onSubmit={handleSubmit}>
        <div>
          <label>Email</label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}  // Update state on each keystroke
            required  // Browser-level validation — won't submit if empty
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
        <button type="submit">Login</button>
      </form>

      <p>Don't have an account? <Link to="/register">Register</Link></p>
    </div>
  );
}

export default Login;
