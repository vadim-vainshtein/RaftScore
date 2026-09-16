import {render, screen} from '@testing-library/react'
import App from './App'
import {expect, test} from "vitest";

test('renders App', () => {
    render(<App/>)

    expect(screen.getByRole('main')).toBeInTheDocument()
})