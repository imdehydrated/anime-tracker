const BASE_TOGGLES = [
	{ key: 'includeExtraSeasons', label: 'Extra Seasons' },
	{ key: 'includeMovies', label: 'Movies' },
	{ key: 'includeOnasOvasSpecials', label: 'ONAs / OVAs / Specials' },
	{ key: 'includeMusic', label: 'Music' },
];

const ADULT_TOGGLE = { key: 'includeAdult', label: '18+ Content' };

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
							<div className="filter-select-shell">
								<select
									className="filter-advanced-select"
									value={filters?.popularityAttenuation ?? 'medium'}
									onChange={(e) => setFilters((prev) => ({ ...prev, popularityAttenuation: e.target.value }))}
								>
									<option value="low">Low</option>
									<option value="medium">Medium</option>
									<option value="high">High</option>
								</select>
								<span className="filter-select-chevron" aria-hidden="true">v</span>
							</div>
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
