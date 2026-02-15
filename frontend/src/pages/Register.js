/**
 * Register Page — Creates a new user account.
 *
 * Posts to POST /api/users/register with username, email, and password.
 * On success, redirects to /login (user must log in manually).
 * Uses the auth-form class for centered card styling.
 */
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getApiError } from '../api/client';
import { registerUser } from '../api/authApi';

function Register() {
	const [username, setUsername] = useState('');
	const [email, setEmail] = useState('');
	const [password, setPassword] = useState('');
	const [error, setError] = useState('');

	const navigate = useNavigate();

	/**
	 * handleSubmit — Sends registration data to the backend.
	 * On success: redirects to login page.
	 * On failure: displays error (e.g., duplicate email/username).
	 */
	const handleSubmit = async (e) => {
		e.preventDefault();
		setError('');

		try {
			await registerUser(username, email, password);
			navigate('/login');      // Redirect to login after successful registration
		} catch (err) {
			setError(getApiError(err, 'Registration failed'));
		}
	};

	return (
		<div className="page">
			{/* Centered card form with dark background */}
			<div className="auth-form">
				<h1>Register</h1>

				{error && <p className="error-message">{error}</p>}

				<form onSubmit={handleSubmit}>
					<input
						type="text"
						placeholder="Username"
						value={username}
						onChange={(e) => setUsername(e.target.value)}
						required
					/>
					<input
						type="email"
						placeholder="Email"
						value={email}
						onChange={(e) => setEmail(e.target.value)}
						required
					/>
					<input
						type="password"
						placeholder="Password"
						value={password}
						onChange={(e) => setPassword(e.target.value)}
						required
					/>
					<button type="submit">Register</button>
				</form>

				<p>Already have an account? <Link to="/login">Login</Link></p>
			</div>
		</div>
	);
}

export default Register;
