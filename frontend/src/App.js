// Import React and the useState, useEffect hooks
import React, { useState, useEffect } from 'react';

// Import CSS for this component
import './App.css';

/**
 * App Component
 *
 * This is the main component of our application.
 * It fetches the backend's health check endpoint and displays the result.
 */
function App() {
  // State: Data that can change and causes the component to re-render
  // useState returns [currentValue, functionToUpdateValue]
  const [status, setStatus] = useState('Loading...');  // Default: "Loading..."
  const [error, setError] = useState(null);  // Default: null (no error)

  // useEffect: Runs side effects (like fetching data)
  // The empty array [] means "run once when component mounts"
  useEffect(() => {
    // Async function to fetch data from backend
    async function fetchHealth() {
      try {
        // fetch() is built into browsers - makes HTTP requests
        // We call /api/health (proxied to backend:8080/api/health)
        const response = await fetch('/api/health');

        // Check if request was successful (status code 200-299)
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        // Parse JSON response body
        // response.json() returns a Promise, so we await it
        const data = await response.json();

        // Update state with the "status" field from JSON
        // If backend returns {"status": "ok"}, data.status is "ok"
        setStatus(`Backend ${data.status.toUpperCase()}`);

      } catch (err) {
        // If anything goes wrong (network error, invalid JSON, etc.)
        console.error('Failed to fetch health check:', err);
        setError('Failed to connect to backend');
        setStatus('Backend OFFLINE');
      }
    }

    // Call the async function
    fetchHealth();
  }, []);  // Empty dependency array: run once when component mounts

  // Render: Return JSX that describes what to show
  return (
    <div className="App">
      <header className="App-header">
        <h1>Anime Tracker</h1>

        {/* Conditional rendering: Show status or error */}
        <p className={error ? 'status-error' : 'status-ok'}>
          {status}
        </p>

        {error && (
          <p className="error-message">
            {error}
          </p>
        )}

        <p className="subtitle">
          Full-stack anime list and recommendation app
        </p>
      </header>
    </div>
  );
}

// Export the component so other files can import it
export default App;

/*
 * === DETAILED EXPLANATION ===
 *
 * React Hooks:
 *
 * "Hooks" are functions that let you use React features in functional components.
 *
 * 1. useState:
 *
 * const [value, setValue] = useState(initialValue);
 *
 * - value: Current state value
 * - setValue: Function to update the value
 * - initialValue: Starting value
 *
 * When you call setValue(), React re-renders the component with the new value.
 *
 * Example:
 *   const [count, setCount] = useState(0);  // count = 0
 *   setCount(5);  // count = 5, component re-renders
 *
 * 2. useEffect:
 *
 * useEffect(() => {
 *   // Code to run
 * }, [dependencies]);
 *
 * - First argument: Function to run (the "effect")
 * - Second argument: Dependency array
 *
 * Dependency array controls when the effect runs:
 * - [] (empty): Run once when component mounts
 * - [var]: Run when var changes
 * - No array: Run after every render (usually bad!)
 *
 * Async/Await:
 *
 * JavaScript is single-threaded. Async/await makes asynchronous code look synchronous.
 *
 * Without async/await:
 *   fetch('/api/health')
 *     .then(response => response.json())
 *     .then(data => setStatus(data.status))
 *     .catch(err => setError(err));
 *
 * With async/await:
 *   const response = await fetch('/api/health');
 *   const data = await response.json();
 *   setStatus(data.status);
 *
 * Much cleaner!
 *
 * Fetch API:
 *
 * fetch(url) makes an HTTP request:
 * - Returns a Promise that resolves to a Response object
 * - Response has methods: .json(), .text(), .blob()
 * - Doesn't automatically throw on HTTP errors (404, 500) - you must check response.ok
 *
 * JSX Conditional Rendering:
 *
 * {error && <p>{error}</p>}
 *
 * This is shorthand for:
 *   if (error) {
 *     return <p>{error}</p>;
 *   }
 *
 * && operator:
 * - If left side is falsy (false, null, undefined, 0, ""), return left side
 * - If left side is truthy, return right side
 *
 * So:
 * - error = null: Renders nothing (null is not rendered)
 * - error = "Failed to connect": Renders <p>Failed to connect</p>
 *
 * className vs class:
 *
 * In HTML: <div class="App">
 * In JSX: <div className="App">
 *
 * Why? "class" is a reserved keyword in JavaScript.
 *
 * How this component works:
 *
 * 1. Component mounts (first render)
 * 2. state.status = "Loading...", state.error = null
 * 3. UI shows "Loading..."
 * 4. useEffect runs (after first render)
 * 5. fetchHealth() calls backend
 * 6. Backend responds with {"status": "ok"}
 * 7. setStatus("Backend OK") updates state
 * 8. Component re-renders
 * 9. UI shows "Backend OK"
 */
