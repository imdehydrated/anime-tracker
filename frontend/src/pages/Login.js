/**
 * Login Page — Authenticates user and stores JWT token.
 *
 * Posts credentials to POST /api/users/login.
 * On success, saves the JWT token via AuthContext and redirects to My List.
 * Uses the auth-form class for centered card styling.
 */
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { getApiError } from '../api/client';
import { loginUser } from '../api/authApi';

function Login() {
	const [email, setEmail] = useState('');
	const [password, setPassword] = useState('');
	const [error, setError] = useState('');

	const { login } = useAuth();         // Saves token to context + localStorage
	const navigate = useNavigate();       // Programmatic navigation after login

	/**
	 * handleSubmit — Sends login credentials to the backend.
	 * On success: stores JWT token and redirects to /mylist.
	 * On failure: displays error message.
	 */
	const handleSubmit = async (e) => {
		e.preventDefault(); // Prevent page reload
		setError('');

		try {
			const data = await loginUser(email, password);
			login(data.token);       // Store token in AuthContext
			navigate('/mylist');      // Redirect to My List page
		} catch (err) {
			setError(getApiError(err, 'Login failed'));
		}
	};

	return (
		<div className="auth-page">
			<div className="auth-split-layout">
				<div className="auth-split-brand">
					<p className="auth-split-kicker">Semantic discovery for anime fans</p>
					<h1>Ani<span>Rec</span></h1>
					<p className="auth-split-sub">Your personal anime companion.</p>
					<p className="auth-split-tagline">
						Track shows, discover hidden gems, and get personalized recommendations powered by semantic AI.
					</p>
				</div>
				<div className="auth-form">
					<h1>Login</h1>
					<p className="auth-subtitle">Sign in to save your list, feedback, and recommendation history.</p>

					{error && <p className="error-message">{error}</p>}

					<form onSubmit={handleSubmit}>
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
						<button type="submit" className="btn-primary">Login</button>
					</form>

					<p>Don't have an account? <Link to="/register">Register</Link></p>
				</div>
			</div>
		</div>
	);
}

export default Login;
