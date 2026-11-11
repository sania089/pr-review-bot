import { Route, Routes } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { RequireAuth } from '@/lib/auth'
import { DashboardPage } from '@/pages/DashboardPage'
import { LoginPage } from '@/pages/LoginPage'
import { ReviewDetailPage } from '@/pages/ReviewDetailPage'

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/reviews/:id" element={<ReviewDetailPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
