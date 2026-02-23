import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import NavBar from './components/NavBar';
import Home from './pages/Home';
import Login from './pages/Login';
import Register from './pages/Register';
import MyList from './pages/MyList';
import Search from './pages/Search';
import AnimeDetail from './pages/AnimeDetail';
import SmartRec from './pages/SmartRec';
import RequireAuth from './components/RequireAuth';

// Top-level route map.
// Protected routes are wrapped with RequireAuth.
function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <NavBar />
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/mylist" element={
            <RequireAuth>
              <MyList />
            </RequireAuth>
          } />
          <Route path="/search" element={<Search />} />
          <Route path="/anime/:id" element={<AnimeDetail />}></Route>
          <Route path="/smart-rec" element={<SmartRec />}></Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;
