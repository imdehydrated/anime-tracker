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
		<div className="auth-page">
			<div className="auth-split-layout">
					<div className="auth-split-brand">
						<p className="auth-split-kicker">Track, rate, and refine your taste</p>
						<h1>Ani<span>Rec</span></h1>
						<p className="auth-split-sub">Your personal anime companion.</p>
						<p className="auth-split-tagline">
							Build a list, shape your taste profile, and turn every search into cleaner recommendations.
						</p>
						<div className="auth-feature-pills fade-in-up fade-delay-1">
							<span className="auth-feature-pill">List Import</span>
							<span className="auth-feature-pill">Score Tracking</span>
							<span className="auth-feature-pill">Taste-aware Ranking</span>
						</div>
					</div>
					<div className="auth-form fade-in-up">
						<h1>Register</h1>
						<p className="auth-subtitle">Create an account to track anime and unlock personalized recommendations.</p>

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
							<button type="submit" className="btn-primary">Register</button>
						</form>

						<div className="auth-form-trust">
							<p>Import from AniList or MAL after sign-up.</p>
							<p>Your ratings and progress stay local to your account.</p>
						</div>

					<p className="auth-form-footer">Already have an account? <Link to="/login">Login</Link></p>
				</div>
			</div>
		</div>
	);
}

export default Register;
