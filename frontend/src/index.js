// Import React library
// React is the core library for building UI components
import React from 'react';

// Import ReactDOM - connects React to the browser's DOM
// "DOM" = Document Object Model (the HTML structure in the browser)
import ReactDOM from 'react-dom/client';

// Import our main App component
import App from './App';

// Import CSS styles
import './index.css';

// Get the root DOM element (the <div id="root"> from index.html)
const rootElement = document.getElementById('root');

// Create a React root
// This is the new way in React 18+ (replaces ReactDOM.render)
const root = ReactDOM.createRoot(rootElement);

// Render the App component into the root
// <App /> is JSX - it looks like HTML but it's actually JavaScript
// React.StrictMode is a helper that finds potential problems
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

/*
 * === DETAILED EXPLANATION ===
 *
 * What happens when this file runs:
 *
 * 1. Imports load React, ReactDOM, App component, and CSS
 * 2. document.getElementById('root') finds the <div id="root"> element
 * 3. ReactDOM.createRoot(rootElement) creates a React root
 * 4. root.render(<App />) renders the App component into the root
 *
 * The result: Your React app appears in the browser!
 *
 * React.StrictMode:
 *
 * This is a development tool that:
 * - Warns about deprecated APIs
 * - Warns about unsafe lifecycle methods
 * - Detects unexpected side effects
 *
 * It does NOT render any visible UI - it's just a wrapper that enables extra checks.
 * In production builds, StrictMode is automatically removed.
 *
 * JSX Syntax:
 *
 * <App /> looks like HTML, but it's actually JavaScript!
 *
 * Babel (transpiler) converts:
 *   <App />
 *
 * To:
 *   React.createElement(App, null, null)
 *
 * JSX is syntactic sugar - makes code more readable.
 *
 * Why React 18's createRoot?
 *
 * Old way (React 17 and earlier):
 *   ReactDOM.render(<App />, rootElement);
 *
 * New way (React 18+):
 *   const root = ReactDOM.createRoot(rootElement);
 *   root.render(<App />);
 *
 * Benefits of new API:
 * - Enables concurrent features (automatic batching, transitions)
 * - Better performance
 * - Prepares for future React features
 */
