import { useEffect, useRef, useState } from 'react';

const BASE_TOGGLES = [
	{ key: 'includeExtraSeasons', label: 'Extra Seasons' },
	{ key: 'includeMovies', label: 'Movies' },
	{ key: 'includeOnasOvasSpecials', label: 'ONAs / OVAs / Specials' },
	{ key: 'includeMusic', label: 'Music' },
];

const ADULT_TOGGLE = { key: 'includeAdult', label: '18+ Content' };
const POPULARITY_OPTIONS = [
	{ value: 'low', label: 'Low' },
	{ value: 'medium', label: 'Medium' },
	{ value: 'high', label: 'High' },
];

function FilterCustomSelect({
	value,
	options,
	onChange,
	className = '',
	triggerClassName = '',
	menuClassName = '',
	ariaLabel,
}) {
	const [open, setOpen] = useState(false);
	const rootRef = useRef(null);
	const selectedOption = options.find((option) => option.value === value) || options[0];

	useEffect(() => {
		if (!open) return undefined;

		const handlePointerDown = (event) => {
			if (rootRef.current && !rootRef.current.contains(event.target)) {
				setOpen(false);
			}
		};

		const handleKeyDown = (event) => {
			if (event.key === 'Escape') {
				setOpen(false);
			}
		};

		document.addEventListener('mousedown', handlePointerDown);
		document.addEventListener('keydown', handleKeyDown);

		return () => {
			document.removeEventListener('mousedown', handlePointerDown);
			document.removeEventListener('keydown', handleKeyDown);
		};
	}, [open]);

	return (
		<div ref={rootRef} className={`custom-select ${className}${open ? ' is-open' : ''}`}>
			<button
				type="button"
				className={`custom-select-trigger ${triggerClassName}`}
				onClick={() => setOpen((prev) => !prev)}
				aria-haspopup="listbox"
				aria-expanded={open}
				aria-label={ariaLabel}
			>
				<span className="custom-select-label">{selectedOption.label}</span>
				<span className="custom-select-chevron" aria-hidden="true">v</span>
			</button>
			{open && (
				<div className={`custom-select-menu ${menuClassName}`} role="listbox">
					{options.map((option) => (
						<button
							key={option.value}
							type="button"
							className={`custom-select-option${option.value === value ? ' is-selected' : ''}`}
							onClick={() => {
								onChange(option.value);
								setOpen(false);
							}}
						>
							{option.label}
						</button>
					))}
				</div>
			)}
		</div>
	);
}

function FilterToggleCard({ label, checked, onChange }) {
	return (
		<div className="filter-card">
			<div className="filter-card-title">{label}</div>
			<label className="filter-switch-row">
				<input type="checkbox" checked={checked} onChange={onChange} />
				<span className="filter-switch-slider" />
				<span className="filter-switch-state">{checked ? 'On' : 'Off'}</span>
			</label>
		</div>
	);
}

function FilterControlPanel({
	title = 'Filters',
	filters,
	setFilters,
	showPopularityAttenuation = false,
	showAdultToggle = true,
	showPersonalizationToggle = false,
	personalizationEnabled = false,
	onPersonalizationChange = null,
	personalizationLabel = 'Use List Personalization',
	personalizationHelp = 'Blend your list taste profile into recommendations.',
}) {
	const toggles = showAdultToggle ? [...BASE_TOGGLES, ADULT_TOGGLE] : BASE_TOGGLES;

	return (
		<div className="smart-rec-section">
			<label className="smart-rec-label">{title}</label>
			<div className="filter-toggle-grid">
				{toggles.map((toggle) => (
					<FilterToggleCard
						key={toggle.key}
						label={toggle.label}
						checked={Boolean(filters?.[toggle.key])}
						onChange={(e) => setFilters((prev) => ({ ...prev, [toggle.key]: e.target.checked }))}
					/>
				))}
			</div>
			{showPopularityAttenuation && (
				<div className="filter-advanced-panel">
					<div className="filter-advanced-title">Advanced Options</div>
					<div className={`filter-advanced-grid${showPersonalizationToggle ? '' : ' single'}`}>
						<div className="filter-advanced-field">
							<div className="filter-advanced-label">Popularity Attenuation Factor</div>
							<FilterCustomSelect
								value={filters?.popularityAttenuation ?? 'medium'}
								options={POPULARITY_OPTIONS}
								onChange={(nextValue) => setFilters((prev) => ({ ...prev, popularityAttenuation: nextValue }))}
								className="filter-select-shell"
								triggerClassName="filter-advanced-select"
								menuClassName="filter-advanced-menu"
								ariaLabel="Select popularity attenuation factor"
							/>
							<p className="filter-advanced-help">
								Higher popularity attenuation factors result in less-popular anime being weighted higher in
								recommendations.
							</p>
						</div>
						{showPersonalizationToggle && (
							<div className="filter-advanced-field filter-advanced-checkbox-field">
								<div className="filter-advanced-label">{personalizationLabel}</div>
								<label className="filter-switch-row filter-advanced-switch-row">
									<input
										type="checkbox"
										checked={Boolean(personalizationEnabled)}
										onChange={(e) => {
											if (typeof onPersonalizationChange === 'function') {
												onPersonalizationChange(e.target.checked);
											}
										}}
									/>
									<span className="filter-switch-slider" />
									<span className="filter-switch-state">{Boolean(personalizationEnabled) ? 'On' : 'Off'}</span>
								</label>
								<p className="filter-advanced-help">{personalizationHelp}</p>
							</div>
						)}
					</div>
				</div>
			)}
		</div>
	);
}

export default FilterControlPanel;
